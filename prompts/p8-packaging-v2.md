# P8 · 打包分发（Windows 单文件 + Linux AppImage + 图标 + 版本 + 裁剪）

> 任务书只读。commit：`build(pkg)` 图标与版本、`ci(pkg)` 产物与发布、`docs(p8)` 报告。
> 每个 commit 独立 `./gradlew build` 通过。**不改任何业务逻辑。**

## 0. 先接上 CI

`.github/workflows/build.yml` 现在只在 `ci/windows` 分支上。先把它拿过来：

```bash
git checkout ci/windows -- .github/workflows/build.yml
```

然后按下面第 4、5 条扩写它（现在只有 portable zip + console + msi）。

## 1. 图标

logo 在 `design/logo` 分支（`ymcl-mark-dark.ico` / `ymcl-mark-light.ico` / `-light-512.png`）：

```bash
git checkout design/logo -- design/logo
```

```kotlin
nativeDistributions {
    windows {
        iconFile.set(project.file("../design/logo/ymcl-mark-dark.ico"))
        console = project.hasProperty("ymcl.console")
    }
    linux {
        iconFile.set(project.file("../design/logo/ymcl-mark-light-512.png"))
        menuGroup = "Games"
        appCategory = "Game"
        shortcut = true
    }
}
```

**运行时窗口图标另算**：jpackage 的 `iconFile` 只管 exe 与快捷方式，不管运行中的窗口/任务栏。
要在 `Window(icon = painterResource("ymcl.png"))` 里单独设（资源放
`composeApp/src/commonMain/composeResources/drawable/ymcl.png`，用 256px）。

## 2. 版本号

- `packageVersion = providers.gradleProperty("ymcl.version").orElse("1.0.0").get()`
- CI：`VERSION=$(git describe --tags --always 2>/dev/null | sed 's/^v//')`，形如 `1.0.0` 或 `1.0.0-3-gabc1234`
  → **jpackage 只接受纯数字点分**，所以取前三位：`1.0.0`；完整串写进 `BuildInfo`
- 关于页/设置页显示 `v<packageVersion> (<git sha>)`

## 3. 裁剪运行时（先量后裁）

1. `jdeps --print-module-deps --ignore-missing-deps composeApp/build/.../app/*.jar`
2. 手工补：`java.net.http jdk.crypto.ec jdk.charsets jdk.zipfs jdk.unsupported java.management jdk.localedata`
3. 写进 `nativeDistributions.modules(...)`，**保留 `-Pymcl.allModules` 开关**退回 `includeAllModules`
4. 目标：234MB → **≤120MB**；达不到就记录实测值并说明差在哪，不要为了数字删模块

## 4. Windows 两个产物

**portable zip**（已有）：`YMCL-<ver>-win-x64-portable.zip`，解压即用。

**单文件 exe**（7-Zip SFX）——CI 里：

```powershell
# 7z.exe 在 windows-latest 上通常已存在；没有就 choco install 7zip -y
$app  = "composeApp/build/compose/binaries/main/app/YMCL"
$cfg  = @"
;!@Install@!UTF-8!
Title="YMCL $env:VERSION"
RunProgram="YMCL.exe"
GUIMode="2"
InstallPath="%LOCALAPPDATA%\\YMCL\\$env:VERSION"
;!@InstallEnd@!
"@
Set-Content -Path sfx-config.txt -Value $cfg -Encoding UTF8
& "C:\Program Files\7-Zip\7z.exe" a -sfx7z.sfx "YMCL-$env:VERSION-win-x64.exe" sfx-config.txt ".\$app\*"
```

- 首启解压到 `%LOCALAPPDATA%\YMCL\<ver>`，之后复用（同版本不重复解压）
- Release 正文必须写明"首启会解压 1–3 秒"
- MSI 保留 `continue-on-error`

## 5. Linux AppImage + 发布

- `appimagetool`（`ARCH=x86_64 appimagetool --appimage-extract-and-run <AppDir>`）
- AppDir：`usr/bin/YMCL`（软链到 app-image 的启动器）、`usr/lib/…`、`YMCL.desktop`、`ymcl.png`（256）
- `.desktop`：`Name=YMCL`、`Exec=YMCL`、`Icon=ymcl`、`Categories=Game;`、`Terminal=false`
- 每个产物旁出 `SHA256SUMS`
- 打 `v*` tag 时建 Release（`softprops/action-gh-release`），附件：zip / exe / AppImage / SHA256SUMS
- 正文写：未签名，Windows SmartScreen 会拦（更多信息 → 仍要运行）；列出产物与大小

## 验收（缺一不算过）

1. `build`/`test` 绿；CI 三个 job 全绿（windows portable / console / linux）
2. 产物齐全：`YMCL-<ver>-win-x64.exe`（SFX）、`…-portable.zip`、`…-x86_64.AppImage`、`SHA256SUMS`
3. **干净 Win10 VM**（无 JDK，`where java` 为空）：
   - 双击 SFX exe → 解压 → 窗口起来
   - **exe 文件属性里的图标**是 Y 立方体
   - **任务栏/Alt-Tab 的图标**也是（这条专门验运行时图标）
   - 建 1.20.1 实例 → 进主菜单
4. 运行时体积：裁剪前后字节对比（贴 `du -sh`）
5. AppImage 在 Mayo-WS 上 `chmod +x` 直接运行，能开窗（截图）
6. 版本号：设置页/关于页显示 `v<ver> (<sha>)`，与 tag 一致
7. 二次启动 SFX：不再解压（或明显更快），日志可证
8. 报告：`文件:行`、`git log --oneline`、`git status --porcelain`、各产物大小与 sha256

## 红线

不改业务逻辑/下载器/UI 布局；不加新功能；不为了减小体积删掉运行时必需模块
（`java.net.http` 缺失的教训）；截图窗口级、不许 fallback 全屏。
