import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")

    // HTTP 用 JDK 内置 java.net.http，不引 ktor —— 少一层依赖就少一份体积与冲突风险

    // Android Material 3 组件库（Compose Multiplatform 官方移植）+ Material You 动态取色
    implementation(compose.material3)
    implementation("com.materialkolor:material-kolor:3.0.0-beta01")

    // 二维码渲染（B 站扫码登录，本地生成，不外传）
    implementation("com.google.zxing:core:3.5.3")

    // Windows DPAPI：API Key / Cookie 加密存储
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(17)
}

// Gradle 8.11 的 test worker 把 classpath 写进 manifest-jar 时，非 ASCII 工程路径会被
// 解码成乱码，导致所有测试类 ClassNotFoundException。构建产物落到纯 ASCII 路径绕开它。
layout.buildDirectory.set(File("C:/tmp/ra-build"))

tasks.test {
    useJUnitPlatform()
}

// 包名/版本/中文元数据只写一处，compose 插件配置和下面的 jpackage 任务共用。
val appName = "seedbili"
val appVersion = "1.4.1"
val appVendor = "SEEDTUT LAB"
val appDescription = "B 站评论区自动回复工具"
val appMenuGroup = "seedbili"
val appUpgradeUuid = "6f2b3c81-4a7d-4e52-9c1b-2a7f5d0e9c34"
// burn 安装壳自己的 UpgradeCode（与 MSI 的 UpgradeUuid 是两码事），
// 以后 1.4.x 之间用 exe 升级靠它识别并卸掉旧壳。
val appBundleUpgradeUuid = "6f2b3c81-4a7d-4e52-9c1b-2a7f5d0e9c36"
// Compose 1.12 / skiko 的产物按 Java 21（class 65）发布，编译仍用 17 目标，
// 但运行/打包的运行时镜像必须基于本机的 JDK 25。
val runtimeJdkHome: File = sequenceOf(
    System.getenv("RA_JDK"),
    "C:/Users/Administrator/jdk-25/jdk-25.0.4.1+1"
).filterNotNull().map(::File).firstOrNull { File(it, "bin/jlink.exe").exists() }
    ?: throw GradleException("找不到 JDK 21+ 运行时（需要 jlink）。设置 RA_JDK 指向它。")
val jvmModules = listOf(
    // 只带必需的模块，压体积
    "java.base", "java.desktop", "java.logging", "java.net.http",
    "java.prefs", "java.sql", "java.management", "java.instrument",
    "jdk.unsupported", "jdk.crypto.ec"
)

compose.desktop {
    application {
        mainClass = "com.gaibang.replyagent.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = appName
            packageVersion = appVersion
            vendor = appVendor
            description = appDescription

            modules = ArrayList(jvmModules)

            windows {
                menuGroup = appMenuGroup
                upgradeUuid = appUpgradeUuid
                perUserInstall = true
                shortcut = true
                val ico = file("src/main/resources/app.ico")
                if (ico.exists()) iconFile.set(ico)
            }
        }

        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED"
        )
    }
}

// ============================================================================
// 安装包：自己调 jpackage，不用插件的 packageMsi/packageExe
//
// 插件把所有参数写进一个 @argfile（UTF-8 字节），而 JDK 17 的 jpackage.exe 用
// 系统 ANSI 代码页（这台机器是 GBK）读它 —— 一旦 vendor / description / menuGroup
// 里有中文，解码就抛 "Input length = 1"，打包直接失败。
// 这里改成把参数当宽字符命令行直接递给 jpackage，中文元数据能原样进 MSI。
// ============================================================================
val stageNativeLibs = tasks.register<Sync>("stageNativeLibs") {
    group = "distribution"
    description = "把主 jar 和全部依赖 jar 收进 native/libs，当 jpackage 的 --input"
    from(tasks.named("jar"))
    from(sourceSets.main.get().runtimeClasspath.filter { it.name.endsWith(".jar") })
    into(layout.buildDirectory.dir("native/libs"))
    doLast {
        // material-icons-extended 是 5 套风格 × 2000+ 图标，整包 36MB，而这里只用 rounded 一套。
        // 其余 4 套（filled / outlined / sharp / twotone）在运行时永远不会被加载，删掉省 ~29MB。
        // 不留手写白名单：少一个图标就是启动即崩（QrCode2 已经踩过一次），rounded 全留最稳。
        val libsDir = layout.buildDirectory.dir("native/libs").get().asFile
        val extJar = libsDir.listFiles()?.firstOrNull { it.name.startsWith("material-icons-extended-desktop") }
            ?: return@doLast
        val pruned = File(libsDir, "material-icons-extended.pruned.jar")
        ZipFile(extJar).use { zip ->
            ZipOutputStream(pruned.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith("androidx/compose/material/icons/") && !name.contains("/rounded/")) continue
                    out.putNextEntry(ZipEntry(name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        extJar.delete()
        if (!pruned.renameTo(extJar)) throw GradleException("裁剪后的图标 jar 改名失败")
    }
}

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "用 JDK 25 生成运行时镜像（Compose 1.12 的 class 版本是 65，JDK 17 的镜像加载不了）"
    notCompatibleWithConfigurationCache("命令行受本机 JDK 路径影响")
    val outDir = layout.buildDirectory.dir("native/runtime").get().asFile
    executable = File(runtimeJdkHome, "bin/jlink.exe").path
    args(
        "--module-path", File(runtimeJdkHome, "jmods").path,
        "--add-modules", jvmModules.joinToString(","),
        "--output", outDir.path,
        "--strip-debug", "--no-header-files", "--no-man-pages"
    )
    doFirst { outDir.deleteRecursively() }
}

fun registerInstaller(taskName: String, type: String, subdir: String, patchProductCode: Boolean = false) {
    tasks.register(taskName) {
        group = "distribution"
        description = "jpackage 打 $type 安装包（中文元数据走命令行传参，绕开 argfile 编码坑）"
        dependsOn(stageNativeLibs, jlinkRuntime, "unzipWix")
        notCompatibleWithConfigurationCache("要扫描 jlink 产物目录")

        val buildRoot = layout.buildDirectory.get().asFile
        val libsDir = File(buildRoot, "native/libs")
        val outDir = File(buildRoot, "native/$subdir")
        val wixDir = File(buildRoot, "wix311")
        val icon = File(projectDir, "src/main/resources/app.ico")
        val mainJarName = tasks.named<Jar>("jar").map { it.archiveFileName.get() }

        doLast {
            val jdkHome = runtimeJdkHome
            val jpackage = File(jdkHome, "bin/jpackage.exe")
            if (!jpackage.exists()) throw GradleException("找不到 $jpackage，JDK 21+ 才能运行组件库")
            if (!File(wixDir, "candle.exe").exists()) throw GradleException("WiX 没解压到 $wixDir")

            val runtimeImage = File(buildRoot, "native/runtime")
            if (!File(runtimeImage, "lib/modules").exists()) {
                throw GradleException("没有运行时镜像，先跑 gradle jlinkRuntime")
            }

            outDir.mkdirs()
            // skiko 的 dll 和 icu 数据装在 runtime jar 里。启动参数给了 -Dskiko.library.path=$APPDIR，
            // skiko 就只认 app/skiko-windows-x64.dll 这一个位置，找不到直接 LibraryLoadException，
            // 不会退回「从 jar 里解压」—— 所以打包前必须自己把它们剥到 app 目录。
            val skikoJar = sourceSets.main.get().runtimeClasspath.files
                .singleOrNull { it.name.startsWith("skiko-awt-runtime-") }
                ?: throw GradleException("依赖里没有 skiko runtime jar")
            ZipFile(skikoJar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".dll") || it.name == "icudtl.dat" }
                    .forEach { entry ->
                        val target = File(libsDir, entry.name)
                        zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                    }
            }
            val logFile = File(buildRoot, "native/logs/jpackage-$type.log").apply { parentFile.mkdirs() }
            val tempDir = File(buildRoot, "native/tmp-$type").apply { deleteRecursively(); mkdirs() }

            val args = mutableListOf(
                jpackage.path,
                "--type", type,
                "--name", appName,
                "--app-version", appVersion,
                "--vendor", appVendor,
                "--description", appDescription,
                "--input", libsDir.path,
                "--runtime-image", runtimeImage.path,
                "--main-jar", mainJarName.get(),
                "--main-class", "com.gaibang.replyagent.MainKt",
                "--java-options", "-Dcompose.application.resources.dir=\$APPDIR\\resources",
                "--java-options", "-Dcompose.application.configure.swing.globals=true",
                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "--add-opens",
                "--java-options", "java.desktop/sun.awt=ALL-UNNAMED",
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-Dskiko.library.path=\$APPDIR",
                "--win-dir-chooser", "--win-per-user-install", "--win-shortcut", "--win-menu",
                "--win-menu-group", appMenuGroup,
                "--win-upgrade-uuid", appUpgradeUuid,
                "--dest", outDir.path,
                "--temp", tempDir.path
            )
            if (icon.exists()) {
                args.add("--icon")
                args.add(icon.path)
            }

            logger.lifecycle("jpackage --type $type 输出目录：${outDir.path}")
            val pb = ProcessBuilder(args)
                .directory(buildRoot)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            pb.environment()["WIX"] = wixDir.path
            pb.environment()["PATH"] = wixDir.path + File.pathSeparator +
                (pb.environment()["PATH"] ?: System.getenv("PATH"))

            val exit = pb.start().waitFor()
            // jpackage 的日志按系统 ANSI 代码页写（这台机器 GBK），JDK 17 不认 UTF-8 默认值
            val text = String(logFile.readBytes(), Charset.forName("GBK"))
            logger.lifecycle(text.lines().takeLast(12).joinToString("\n"))
            if (exit != 0) throw GradleException("jpackage 失败（退出码 $exit），完整日志：$logFile")

            if (patchProductCode) {
                // jpackage 的 ProductCode 由「应用名+版本」确定：同一版本重新构建码不变，
                // 已装同版本时 MSI 双击进维护模式（用户看到的「修复」），exe 启动 msiexec 直接 1638。
                // 打完补丁每次构建都是随机新码，覆盖安装走全新安装。
                val outMsi = File(outDir, "$appName-$appVersion.msi")
                val patchPs = File(projectDir, "tools/patch-msi.ps1")
                val pb2 = ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", patchPs.path, "-MsiPath", outMsi.path
                ).redirectErrorStream(true).inheritIO()
                val exit2 = pb2.start().waitFor()
                if (exit2 != 0) throw GradleException("MSI 补丁失败（退出码 $exit2）：$outMsi")
            }
            logger.lifecycle("安装包目录：${outDir.path}")
        }
    }
}

registerInstaller("nativeMsi", "msi", "msi", patchProductCode = true)

// jpackage 的 exe 只是个启动 MSI 的 stub（不是 burn bundle），管不了旧版本卸载。
// 换成自建 WiX burn 壳：先弹协议页，再把补丁版 MSI 链进去；Bundle 自带
// MajorUpgrade，以后用 exe 升级会自动卸掉旧壳。素材在 tools/bundle/ 下。
tasks.register("nativeExe") {
    group = "distribution"
    description = "WiX burn 壳 + 补丁版 MSI → $appName-$appVersion.exe"
    dependsOn("nativeMsi")
    notCompatibleWithConfigurationCache("要扫描 nativeMsi 产物")

    val buildRoot = layout.buildDirectory.get().asFile
    val wixDir = File(buildRoot, "wix311")
    val msiFile = File(buildRoot, "native/msi/$appName-$appVersion.msi")
    val srcDir = File(projectDir, "tools/bundle")
    val workDir = File(buildRoot, "native/exe-src")
    val outFile = File(buildRoot, "native/exe/$appName-$appVersion.exe")

    doLast {
        if (!File(wixDir, "candle.exe").exists()) throw GradleException("WiX 没解压到 $wixDir")
        if (!msiFile.exists()) throw GradleException("没找到补丁版 MSI：$msiFile，先跑 gradle nativeMsi")
        workDir.deleteRecursively()
        workDir.mkdirs()

        listOf("bundle.wxs", "zh-cn.wxl").forEach { name ->
            File(workDir, name).writeText(
                File(srcDir, name).readText()
                    .replace("@NAME@", appName)
                    .replace("@VERSION@", appVersion)
                    .replace("@VENDOR@", appVendor)
                    .replace("@UPGRADE_CODE@", appBundleUpgradeUuid.uppercase())
                    .replace("@MSI_PATH@", msiFile.path)
            )
        }
        copy { from(File(srcDir, "license.rtf")); into(workDir) }
        copy { from(File(srcDir, "logo.png")); into(workDir) }
        copy { from(File(projectDir, "src/main/resources/app.ico")); into(workDir) }

        fun wixTool(exe: String, vararg args: String) {
            val pb = ProcessBuilder(listOf(File(wixDir, exe).path) + args.toList())
                .directory(workDir)
                .redirectErrorStream(true)
                .inheritIO()
            pb.environment()["WIX"] = wixDir.path
            val exit = pb.start().waitFor()
            if (exit != 0) throw GradleException("$exe 失败（退出码 $exit）")
        }
        wixTool("candle.exe", "-arch", "x64", "-ext", "WixBalExtension", "bundle.wxs", "-out", "bundle.wixobj")
        wixTool("light.exe", "-ext", "WixBalExtension", "-loc", "zh-cn.wxl", "-out", outFile.path, "bundle.wixobj")
        logger.lifecycle("安装包目录：${outFile.parentFile.path}")
    }
}

tasks.register<JavaExec>("makeIco") {
    group = "distribution"
    description = "用 AppIcon 画出来的像素生成安装包图标 src/main/resources/app.ico"
    mainClass.set("com.gaibang.replyagent.tools.MakeIcoKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("iconPreview") {
    group = "distribution"
    description = "把 AppIcon 的大尺寸渲染导出为 PNG，便于人工检查图标"
    mainClass.set("com.gaibang.replyagent.tools.IconPreviewKt")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add { listOf("C:/tmp/icon-preview.png") }
}
