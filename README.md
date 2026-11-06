# seedbili — B 站评论区自动回复 Agent（PC 端工具）

Windows 桌面程序：抓自己投稿的评论 → 本地规则先过滤 → 交给 OpenAI 兼容的大模型判断 → 过了护栏才发回复 → 全程落台账。
定位是「人只做配置和管理」，工具不代人登录、不碰密码。

官网（在线文档与截图）：<https://seedbili.seedtut.xyz> · 更新日志见 [Releases](https://github.com/seedtut/seedbili/releases)

需求与决策都在 `docs/` 里，本文件只讲怎么用、怎么改。

---

## 一、第一次启动：片头 + 四步向导

从 [Releases](https://github.com/seedtut/seedbili/releases/latest) 下载 `seedbili-1.4.1.exe`，双击安装（per-user，不需要管理员权限），
装完在「开始菜单 → seedbili」里能找到。

第一次打开会先播一段约 2 秒的片头动画，然后进四步向导，走完就能跑第一轮：

1. **准备环境** — 先选命令在哪执行，两张卡片二选一：
   - **本机 cmd**：不用装任何东西、不占空间、不要管理员权限，直接进下一步；代价是没有文件系统隔离。
   - **WSL + Debian**：点「开始自动安装」，工具自己按顺序执行：检查 WSL → 开启 WSL 功能 →
     安装 Debian → apt 补最小工具链 → 验证。每步的实时输出都打在界面上，装完自动进下一步；
     要重启的话会写清楚，重启后重新打开会自动回到这一步。开启 WSL 那一步会弹一次 **UAC**，
     必须你本人点「是」（不静默提权）。选错了随时点卡片或「不装了，换成本机 cmd」互换。
   两个都不想要就点「两个都不用，先跳过」，之后随时能在「Linux 环境」页再装/再换。
2. **扫码登录** — 进入这一页就自动生成二维码，手机 B 站 App 扫一下、点确认，成功后自动进下一步。
3. **模型 API** — 填 Base URL / 模型名 / Key，点「保存并自检」。自检不是 ping，会真的要求模型调用一次
   假工具；通过后自动进下一步。
4. **开始使用** — 三件事的完成状态一览，点「进入主界面」。

以后想再看一遍：**设置 → 首次引导 → 重新走一遍引导**（已保存的配置和登录态不受影响）。

## 二、主界面怎么用

1. **模型 API** 页：Key 用 Windows DPAPI 加密后落在 `%APPDATA%\ReplyAgent\secrets\`，
   配置文件里只留句柄 `apikey.<id>`，看不到明文；输入框本身也不回显。支持多套配置并存。
2. **登录** 页：扫码为主，扫不了就用「粘贴 Cookie」兜底，Cookie 同样加密落盘。
   界面里没有任何密码框 —— 这是刻意的设计，工具只提供「打开登录 / 查登录状态 / 提示重新登录」。
3. **运行** 页：贴一个投稿链接、BV 号或 av 号（也能从「目标投稿」下面的最近地址里点一个）。
4. 先点 **试跑**：整条链路真跑，但一条都不发出去，本该发的结论全部推进「收件箱」给人看。
5. 确认没问题再点 **开始**。发送之间有 20–45s 的随机间隔（可在策略配置里改），连续失败到上限会自动暂停本轮，
   避免继续撞风控。
6. **收件箱** 页：模型没把握、规则拦下或试跑里「本该发」的评论都收在这里 —— 重启后也在。
   在输入框里改几个字，点「按这段发送」就直接以你的登录态回出去；不想回就点「这条不回」。
   发送失败的条目会留在收件箱里，随时可以再试。
7. **台账** 页看每条的结论和原因；同一条评论不会被回两次，靠的是这份落盘记录，不是模型的记性。

没有 B 站账号也能把链路跑通：平台列表里选「演示平台（假数据）」，10 条假评论包含楼中楼、黑名单、
已回过、提示词注入探针和风控重复码，专门用来自检。

## 三、数据都在哪

`%APPDATA%\ReplyAgent\` 下：

| 目录 | 内容 |
|---|---|
| `config/app.json` | 全部策略、API 配置、提示词。可导入导出 |
| `secrets/*.sec` | API Key、Cookie。文件头一字节标加密方式（DPAPI / AES-GCM） |
| `data/ledger.jsonl` | 台账，追加写 + `fd.sync()`，崩溃后重启仍读得到 |
| `logs/app-YYYY-MM-DD.log` | 日志，按天滚动，写入前做脱敏 |
| `plugins/*.jar` | 平台适配器插件放这里，启动时 ServiceLoader 扫描 |
| `exports/` | 台账导出的 CSV（带 BOM，Excel 双击不乱码） |

设置页有「打开数据目录」，以及危险操作里的「清空台账」「恢复出厂配置」。卸载程序本身不会删这个目录 ——
想彻底抹掉痕迹就是去那里删一遍，故意的：宁可让你确认，也不替你丢数据。

## 四、安全边界（写进代码，不给开关）

- **不接触密码**：没有密码输入框，没有代填、没有代登录。登录能力只有 打开 / 查状态 / 提示重新登录。
- **敏感值加密落盘**：DPAPI（当前用户绑定）。非 Windows 退化成 AES-GCM，界面会明说保护等级降了。
- **提示词注入防护是唯一硬编码规则**：不可信评论先去零宽/控制字符，再把行首角色标记和聊天模板定界符
  中和成文本，最后包在 `<untrusted_comment_data>` 里；模型输出发送前还要过一次护栏（长度、内部标记复述、
  禁用词、链接域名白名单）。**策略配置里没有、也不该有「关掉注入防护」这个勾**。
- **导入配置时拒绝明文凭据**：文件里出现 `sk-...` 或 `SESSDATA=` 直接拒收。
- **保守节奏**：默认间隔 20s + 抖动，单轮 10 条 / 单日 20 条，撞 12014（限流）进冷却，12051（重复）记为已处理不重试。
  工具不做任何绕过风控的能力。

## 五、开发

环境：JDK 17、Gradle 8.11+（工程不带 wrapper）、Windows。工程根是 `app/`。

```bash
cd app
gradle run            # 起窗口，开发用
gradle test           # 单元 + 端到端，全绿才算数
gradle makeIco        # 用 AppIcon 画的像素重新生成安装包图标
gradle nativeMsi      # 出 MSI
gradle nativeExe      # 出 EXE 引导式安装包
```

产物在 `C:/tmp/ra-build/native/{msi,exe}/`。装包前建议跑一次冒烟脚本（管理员 PowerShell）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\verify-msi.ps1 `
  -Msi C:\tmp\ra-build\native\msi\seedbili-1.4.1.msi -Out C:\tmp\ra-verify
```

它会用 `msiexec /a` 把包装到一个临时目录，核对「jar 个数 == `app.classpath` 行数」，
然后真的启动一遍并检查窗口标题——只验证「能装、能起」，不代表功能全对。

⚠ **三个坑**，都踩过：

1. **构建目录重定向到 `C:/tmp/ra-build`**。Gradle 8.11 的 test worker 把 classpath 写进 manifest-jar 时，
   非 ASCII 的工程路径（本项目在 `D:\自动回复项目`）会被解码成乱码，所有测试类报 `ClassNotFoundException`，
   看起来像「测试没编译出来」。别顺手把它删掉。
2. **不能用 compose 插件自带的 `packageMsi`**。插件会把 jpackage 参数写进 UTF-8 的 `@argfile`，
   JDK 17 的 `jpackage.exe` 却按 ANSI（GBK）解码，中文 vendor/菜单组直接报
   `MalformedInputException: Input length = 1`。所以 `nativeMsi`/`nativeExe` 是自己用 `ProcessBuilder`
   调 `jpackage`（宽字符命令行），中文元数据才不会坏。
3. **skiko 原生库必须物理落盘**。`skiko-windows-x64.dll` 和 `icudtl.dat` 没被放进 `$APPDIR` 时，
   装完双击能起进程但窗口永远不出来，只在控制台报 `LibraryLoadException`。`stageNativeLibs`
   任务会从 `skiko-awt-runtime-windows-x64-*.jar` 里把这两个文件解出来再打包。

另外：MSI 同版本重装会被 Windows 拒绝（错误 1638），升级要涨版本号（`appVersion`）。

测试覆盖（`app/src/test/`，全部真跑，不打桩业务逻辑）：

- `WbiTest` — B 站 WBI 签名，含公开文档的已知答案向量。签名表错一位所有签名接口就全 403。
- `FilterEngineTest` — 回复范围、自己/主 UP/黑名单、长度窗口、关键词白黑名单、自定义规则三种动作与顺序。
- `GuardrailsTest` — 清洗与发送前护栏。
- `LedgerServiceTest` — 重启后仍认得、写坏半行要跳过、按保留期清理真的落到文件、CSV 转义。
- `RateLimiterTest` — 假时钟跑节奏：间隔、单轮/单日上限、冷却、指数退避、连续失败熔断。
- `ConfigAndSecretsTest` — 配置导出→导入全字段往返、坏配置留备份、密文仓篡改后 fail-closed。
- `EndToEndRunTest` — 起一个真的本地 HTTP 服务当模型，跑完整轮：10 条抓进来、3 条本地拦下、
  只有 7 条产生 API 调用（**被过滤掉的不该花钱，这是断言出来的**）、6 发 1 失败、台账幂等、
  试跑零发送、注入探针被关在标签内、把握度不足全部转人工、撞护栏的一条都发不出去。

模型侧只用 OpenAI 的 `chat/completions` + function calling，HTTP 走 JDK 内置 `java.net.http`，
没有 ktor。UI 是 Compose Desktop + **Material 3**（`compose.material3`，Android 设计语言），
配色由 material-kolor 从品牌橙做 Material You 动态取色；页面只写语义色与布局，
不手搓控件和调色板。

## 六、加一个新平台

实现 `domain/port/PlatformAdapter`（抓取 / 发回复 / 登录 / 能力声明），打成 jar 丢进
`plugins/`，业务代码零改动 —— 引擎只认接口。参考实现看 `platform/demo/`（最小）和 `platform/bilibili/`（完整）。
需求书要求的「移除 B 站适配器只留接口仍可编译」由 `demo` 平台天然证明。

## 七、已知限制

1. 首版无定时轮询、无专栏/动态支持，只处理视频评论区。
2. B 站接口无官方承诺，WBI 签名表和风控码可能随改版失效；接口层已集中在 `platform/bilibili/`，改版只动那个目录。
3. 体积：JVM 路线装完约 153MB（实测见第八节），做不到 Flutter/Tauri 那种量级。
4. 环境自动安装只覆盖 Windows 的 WSL 路线：需要一次 UAC、部分系统要重启，Debian 下载 200–400MB。
   `wsl.exe` 被组策略/精简系统阉割时脚本会明确报错并给出手动命令，不会假装成功。
   引导第 1 步可以直接选「本机 cmd」绕开 WSL：不用装、不要权限，但没有文件系统隔离。
5. 「界面内嵌浏览器显示登录页」被换成官方扫码登录，理由与代价见 `docs/05-待确认清单.md` 第 1 条。
6. 同版本 MSI 不能覆盖安装（Windows 的 1638），换版本号才能升级。

## 八、体积（实测）

| 项 | 实测 |
|---|---|
| jlink 运行时（10 个模块，已 strip） | 79MB |
| skiko 原生库 + icudtl | 约 24MB |
| 应用 jar + 三方依赖 | 约 50MB |
| 装完占用 | 约 153MB |
| MSI | 85.4MiB |
| EXE 引导安装包 | 86.3MiB |

数字来自 `seedbili-1.4.0` 的构建产物；改依赖或加模块后按同样方法重新量。

`material-icons-extended` 是 36MB（5 套风格 × 2000+ 图标），`stageNativeLibs` 里只保留 rounded 一套
（7.7MB），这一项就省了 29MB。⚠ 别改成手写图标白名单：漏一个（比如 `QrCode2`）就是启动直接
`ClassNotFoundException`，按风格整留最省心。

## 九、还没验证的事（需要你参与）

见 `docs/05-待确认清单.md` 的 B 段：真实中转接口是否支持 `tools` 字段、你自己的账号扫码与发一轮、
手机端那份最好用的提示词原文、主 UP 与黑名单的真实 UID、以及你能接受的发送节奏。
这些工具无法自证，我不假称已测。

## 十、许可

MIT，见 [LICENSE](LICENSE)。本仓库 `website/` 是官网源码（Astro 静态站）。

做这个工具不挣钱；如果它帮你省了时间，[请我喝杯奶茶](https://afdian.com/a/gaibang)。
