# P8：打包分发（Windows 单文件 + Linux AppImage + 图标 + 版本号）

> 任务书只读。commit：`build(pkg)` 图标/版本/裁剪、`ci(pkg)` SFX 与 AppImage、`docs(p8)`。
> 每个 commit 独立 `./gradlew build` 通过。

## 目标

朋友双击一个文件就能用：Windows 一个 exe（或一个 zip），Linux 一个 AppImage，
不需要装 JDK、不需要装启动器。图标用 `design/logo/`（已提交在 `design/logo` 分支）。

## 1. 图标接进去

```kotlin
nativeDistributions {
    windows {
        iconFile.set(project.file("../design/logo/ymcl-logo.ico"))
        // console 开关保留 -Pymcl.console
    }
    linux {
        iconFile.set(project.file("../design/logo/ymcl-logo-512.png"))
        menuGroup = "Games"
        appCategory = "Game"
    }
}
```

运行时窗口图标也要设（任务栏/Alt-Tab）：`Window(icon = painterResource("ymcl-logo-256.png"))`，
资源放 `composeApp/src/commonMain/composeResources/drawable/`。

验收：Windows 任务栏、桌面快捷方式、Alt-Tab、exe 文件属性里的图标都是新 logo。

## 2. 版本号

- `packageVersion` 从 `-Pymcl.version=1.2.3` 读，缺省 `1.0.0`；CI 用 `git describe --tags`
- 关于对话框/设置页显示版本 + git commit（`BuildInfo.kt`，编译期常量或资源注入）
- 版本号必须符合 jpackage 要求（主版本 ≥1，形如 `1.2.3`），CI 里做校验

## 3. 运行时裁剪（在 includeAllModules 之后做，别提前）

1. 对 `app/*.jar` 跑 `jdeps --print-module-deps --ignore-missing-deps`，得到模块集
2. 手工补上反射/运行时才用到的：`jdk.crypto.ec jdk.charsets jdk.zipfs jdk.unsupported java.management java.net.http jdk.localedata`
3. 把结果写进 `nativeDistributions.modules(...)`，**保留** `-Pymcl.allModules` 开关能退回 `includeAllModules`
4. 验收：裁剪后的 app-image 在干净环境启动、能装版本、能启动游戏；runtime 体积记录在报告里
   （当前 234MB，目标 ≤ 120MB）

## 4. Windows：两个产物

- **portable zip**（已有）：`YMCL-<ver>-win-x64-portable.zip`，解压即用
- **单文件 exe**：7-Zip SFX
  - `7z a -sfx7z.sfx YMCL-<ver>-win-x64.exe <app-image 目录>`
  - 配 `config.txt`：`;!@Install@!UTF-8!` + `RunProgram="YMCL.exe"` + `GUIMode="2"` +
    `InstallPath="%LOCALAPPDATA%\\YMCL\\<ver>"`（首启解到这个目录，之后复用）
  - 说明：单文件 exe 本质是自解压，**首启要解压 1–3 秒**，文档里写清楚，别让用户以为卡了
  - MSI 保留为可选产物（`continue-on-error`）

## 5. Linux：AppImage

- `appimagetool` 从 app-image 生成 `YMCL-<ver>-x86_64.AppImage`
- 需要 `YMCL.desktop`（`Name=YMCL`、`Exec=YMCL`、`Icon=ymcl`、`Categories=Game;`）+ 图标 256px
- 验收：`chmod +x` 后直接运行，能用中文、能启动游戏

## 6. 发布产物

- 每个产物旁边放 `SHA256SUMS`，CI 里算
- tag 形如 `v1.0.0` 时创建 Release，附 zip / exe / AppImage / SHA256SUMS
- 未签名说明写进 Release 正文：Windows SmartScreen 会拦，需要"更多信息 → 仍要运行"

## 验收（缺一不算过）

1. `build`/`test` 绿；CI 三个 job 全绿
2. 产物清单：`YMCL-<ver>-win-x64-portable.zip`、`YMCL-<ver>-win-x64.exe`、`YMCL-<ver>-x86_64.AppImage`、`SHA256SUMS`
3. **干净 Win10 VM**：单文件 exe 双击 → 首启解压 → 窗口起来（图标正确）→ 建 1.20.1 实例 → 启动进主菜单
4. **无 JDK 验证**：VM 里 `where java` 为空的前提下跑通
5. 运行时体积：报告里给裁剪前后对比（字节）
6. Linux：AppImage 在 Mayo-WS 上直接跑起来（截图）
7. 版本号：窗口标题/关于页显示 `v<ver> (<commit>)`，与 tag 一致
8. 报告：`文件:行`、`git log --oneline`、`git status --porcelain`、各产物大小与 sha256

## 不要做

改业务逻辑、改下载器、加新页面、登录认证、P6 诊断实现。
