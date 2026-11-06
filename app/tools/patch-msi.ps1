# 给构建出来的 MSI 打两块补丁，解决「同版本重装报 1638 / MSI 双击变修复」：
#
# 1) ProductCode 换新随机 GUID + PackageCode 换新 GUID。
#    jpackage 的 ProductCode 是按应用名+版本确定性生成的 —— 同一版本重新构建，码不变；
#    已装同版本时：MSI 双击进维护模式（用户看到的「修复」对话框），EXE 内嵌 MSI 走安装
#    直接 ERROR_PRODUCT_VERSION(1638)「已安装这个产品的另一个版本」。
#    换成每次构建唯一的码之后，重装走正常安装流程。
#
# 2) Upgrade 表 JP_UPGRADABLE_FOUND 行 Attributes 257 -> 260：
#    去掉 OnlyDetect(1) 位、加上 VersionMaxInclusive(4) 位。
#    jpackage 出的 MSI 两条 Upgrade 行都带 OnlyDetect，RemoveExistingProducts 什么都删不掉；
#    改完这行变成「版本 <= 当前版本的产品一律先卸载」，同版本重装 = 干净的卸载+重装。
#    JP_DOWNGRADABLE_FOUND 行保持 OnlyDetect 不动，降级仍被 MSI 引擎挡下。
#
# 注意 MSI COM 的坑（都踩过）：
# - PowerShell 的 RCW 释放是惰性的，任何 Database/View/Record 句柄没真正关掉，
#   后面 transact 打开或 SummaryInformation 都会报 OpenDatabase/SummaryInformation 失败；
# - Upgrade 表不能用 SQL UPDATE（报 Execute,Params），Modify(2) 也不行
#   （报 Modify,Mode,Record），只能整行取出来用 Modify(3, Merge) 写回。
#
# 用法：powershell -ExecutionPolicy Bypass -File tools/patch-msi.ps1 -MsiPath <msi 路径>
param(
    [Parameter(Mandatory = $true)][string]$MsiPath
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $MsiPath)) { throw "MSI not found: $MsiPath" }

function Release([object]$obj) {
    if ($null -ne $obj -and $obj -is [System.__ComObject]) {
        [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($obj) | Out-Null
    }
}

$msi = New-Object -ComObject WindowsInstaller.Installer
$db = $msi.OpenDatabase($MsiPath, 1)

$oldPc = ""
$v = $db.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductCode'")
$v.Execute()
while ($r = $v.Fetch()) { $oldPc = $r.StringData(1); Release($r) }
$v.Close()
Release($v)
$v = $null

# Upgrade 行改属性：SQL UPDATE 对这张表报 Execute,Params，Modify(2) 报
# Modify,Mode,Record，Merge(3) 会因为没有主键插出重复行 —— 唯一可靠的做法是
# 整行读出来、DELETE 原行、按新 Attributes INSERT 回去。
$oldAttr = 0
$uc = ""
$vma = ""
$v = $db.OpenView("SELECT * FROM ``Upgrade`` WHERE ``ActionProperty`` = 'JP_UPGRADABLE_FOUND'")
$v.Execute()
$row = $v.Fetch()
if ($null -eq $row) { throw "Upgrade table has no JP_UPGRADABLE_FOUND row" }
$oldAttr = $row.IntegerData(5)
$uc = $row.StringData(1)
$vma = $row.StringData(3)
$v.Close()
Release($v)
Release($row)
$row = $null
$v = $null
if ($oldPc -eq "") { throw "Property table has no ProductCode" }

$newPc = [guid]::NewGuid().ToString("B").ToUpper()
$newAttr = ($oldAttr -bor 4) -band (-bnot 1)

$v = $db.OpenView("UPDATE ``Property`` SET ``Value`` = '" + $newPc + "' WHERE ``Property`` = 'ProductCode'")
$v.Execute()
$v.Close()
Release($v)
$v = $null

$v = $db.OpenView("DELETE FROM ``Upgrade`` WHERE ``ActionProperty`` = 'JP_UPGRADABLE_FOUND'")
$v.Execute()
$v.Close()
Release($v)

$v = $db.OpenView("INSERT INTO ``Upgrade`` (``UpgradeCode``, ``VersionMin``, ``VersionMax``, ``Language``, ``Attributes``, ``Remove``, ``ActionProperty``) VALUES ('" + $uc + "', '', '" + $vma + "', '', " + $newAttr + ", '', 'JP_UPGRADABLE_FOUND')")
$v.Execute()
$v.Close()
Release($v)
$v = $null

# jpackage 的 JpFindRelatedProducts（wixhelper.dll!FindRelatedProductsEx）会在跑完之后
# 把 JP_UPGRADABLE_FOUND / MIGRATE / JP_DOWNGRADABLE_FOUND 三个属性整个删掉，
# 让 RemoveExistingProducts 永远拿不到要卸载的产品清单 —— 所以 jpackage 的 MSI
# 从来不会自己卸旧版。删掉这个自定义动作（CA 表 + 两条序列表），上面的补丁才生效。
foreach ($table in @('`CustomAction`', '`InstallExecuteSequence`', '`InstallUISequence`')) {
    $v = $db.OpenView("DELETE FROM " + $table + " WHERE ``Action`` = 'JpFindRelatedProducts'")
    $v.Execute()
    $v.Close()
    Release($v)
    $v = $null
}

$db.Commit()
Release($db)
$db = $null
Release($msi)
$msi = $null
[gc]::Collect()
[gc]::WaitForPendingFinalizers()

# summary information：包码也换成新的，避免源缓存里两个包撞包码
# 包码必须带大括号（ToString("D") 不带，引擎直接报 87 invalid parameter）
$msi = New-Object -ComObject WindowsInstaller.Installer
$si = $msi.SummaryInformation($MsiPath, 5)
$si.Property(9) = [guid]::NewGuid().ToString("B").ToUpper()
$si.Persist()
Release($si)
$si = $null
Release($msi)
$msi = $null

Write-Host ("patched  : " + $MsiPath)
Write-Host ("code     : " + $oldPc + " -> " + $newPc)
Write-Host ("upgrade  : attr " + $oldAttr + " -> " + $newAttr + " (JP_UPGRADABLE_FOUND now removes versions <= current)")
Write-Host ("package  : new random package code")
