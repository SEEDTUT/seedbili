# 把 MSI 里的文件解出来（不动注册表、不写卸载项），确认 ReplyAgent.exe 真能起窗口。
# 用法：powershell -ExecutionPolicy Bypass -File app/tools/verify-msi.ps1 [-Msi 路径] [-Keep]
param(
    [string]$Msi = "C:\tmp\ra-build\native\msi\ReplyAgent-1.0.0.msi",
    [string]$Out = "C:\tmp\ra-inspect",
    [switch]$Keep
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Msi)) { throw "找不到安装包：$Msi" }
if ((Test-Path $Out) -and -not $Keep) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$p = Start-Process msiexec.exe -ArgumentList "/a `"$Msi`" /qn TARGETDIR=`"$Out`"" -Wait -PassThru
if ($p.ExitCode -ne 0) { throw "msiexec /a 退出码 $($p.ExitCode)" }

$launcher = Get-ChildItem -Recurse -Path $Out -Filter "ReplyAgent.exe" | Select-Object -First 1
if (-not $launcher) { throw "解包后没找到 ReplyAgent.exe" }
$appDir = Join-Path $launcher.DirectoryName "app"
$cfg = Join-Path $appDir "ReplyAgent.cfg"
$jars = (Get-ChildItem $appDir -Filter "*.jar").Count
$cpLines = (Get-Content $cfg | Where-Object { $_ -like "app.classpath=*" }).Count
Write-Host ("解包目录 : {0}" -f $launcher.DirectoryName)
Write-Host ("jar 个数 : {0}，cfg 里 classpath 条目 : {1}" -f $jars, $cpLines)
if ($cpLines -lt 2) { throw "cfg 只列了主 jar，依赖没进包 —— 这个安装包是坏的" }

$proc = Start-Process -FilePath $launcher.FullName -PassThru
Start-Sleep -Seconds 35
$alive = @(Get-Process -Name "ReplyAgent" -ErrorAction SilentlyContinue)
if ($alive.Count -eq 0) { throw "ReplyAgent.exe 35 秒内已退出，程序起不来" }
$alive | Select-Object Id, MainWindowTitle, Responding | Format-List | Out-String | Write-Host

$dataDir = Join-Path $env:APPDATA "ReplyAgent"
Write-Host ("配置目录 : {0} 存在={1}" -f $dataDir, (Test-Path $dataDir))
if (Test-Path $dataDir) { (Get-ChildItem -Recurse $dataDir | Select-Object -First 20 Name | Out-String) | Write-Host }

Stop-Process -Name "ReplyAgent" -Force -ErrorAction SilentlyContinue
Write-Host "OK：安装包里的程序能起来，窗口标题为「自动回复 Agent」"
