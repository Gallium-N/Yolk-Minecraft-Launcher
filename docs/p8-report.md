# P8 · 打包分发 报告

## 改动文件（file:line）

| 文件:行 | 用途 |
|---|---|
| `composeApp/build.gradle.kts:14-45` | `-Pymcl.version`（默认 1.0.0）+ git sha → 生成 `BuildInfo.kt`；`:52-56` 注册为 commonMain 源、`:136` 让 Kotlin 编译依赖它 |
| `composeApp/build.gradle.kts:90` | `packageVersion = ymclVersion` |
| `composeApp/build.gradle.kts:94-119` | jlink 模块裁剪（15 模块，含 `java.net.http` 等必需模块）+ `-Pymcl.allModules` 回退（`:97`）|
| `composeApp/build.gradle.kts:121-130` | Windows `ymcl-mark-dark.ico` / Linux `ymcl-mark-light-512.png` + `menuGroup=Games` / `appCategory=Game` / `shortcut` |
| `composeApp/src/desktopMain/kotlin/dev/mayo/ymcl/DesktopMain.kt:22,142` | 运行时窗口图标 `painterResource(Res.drawable.ymcl)` |
| `composeApp/src/commonMain/composeResources/drawable/ymcl.png` | 256×256，复制自 `ymcl-mark-light-256.png` |
| `composeApp/src/commonMain/kotlin/dev/mayo/ymcl/ui/pages/SettingsPage.kt:456` | 关于页 `v${BuildInfo.VERSION} (${BuildInfo.SHA})` |
| `.github/workflows/build.yml:15,37,90,142` | 四个必绿 job：test / windows-portable / windows-sfx / linux-appimage |
| `.github/workflows/build.yml:202,225` | console-debug / MSI best-effort（`continue-on-error: true`）|
| `.github/workflows/build.yml:248` | release job，`if: startsWith(github.ref, 'refs/tags/v')` |
| `docs/p8-manual-checklist.md` | Win10 手动验收清单（用户自验）|

DSH 决策回执见 `prompts/p8-packaging-v2.md:113-143`（与上文冲突以该节为准）。

## git log --oneline

```
8cc89cf ci(pkg): required SFX/AppImage jobs, best-effort MSI, tagged release
c06afa8 build(pkg): icons, version info, jlink module trimming
0e03c25 chore: take logo + p8 decision addendum
93bae6a docs(p8): packaging book v2 — icon, version, trim, SFX, AppImage, release
2aec263 feat(java): read-only discovery, matrix selection, auto-install
```

`git status --porcelain`：仅剩本报告 `docs/p8-report.md`（提交本报告后为空）。
tag：`v1.0.0` → 8cc89cf；`git describe --tags --match 'v*' --abbrev=0 | sed 's/^v//'` = `1.0.0`（与 tag 一致）。

## 运行时体积（裁剪前后实测）

本机为 Linux，jpackage 生成 app-image；用同一台机器、同一 JDK 21、相同构建参数两次构建，仅切换 `-Pymcl.allModules`：

| 构建 | app-image 总大小 | `lib/runtime` 大小 |
|---|---|---|
| `-Pymcl.allModules`（裁剪前） | **234 MB** | 169 MB |
| 默认 jdeps 裁剪（15 模块，裁剪后） | **186 MB** | **121 MB** |

`du -sh` 原始输出：
```
234M    composeApp/build/compose/binaries/main/app/YMCL   # allModules
169M    composeApp/build/compose/binaries/main/app/YMCL/lib/runtime
186M    composeApp/build/compose/binaries/main/app/YMCL   # trimmed
121M    composeApp/build/compose/binaries/main/app/YMCL/lib/runtime
```

jdeps 模块来源：`jdeps --print-module-deps --ignore-missing-deps --multi-release 21 lib/app/*.jar`（排除未使用的 javafx / fx-gson 桥接 jar）得到
`java.base, java.desktop, java.instrument, java.net.http, java.sql, jdk.attach, jdk.management, jdk.unsupported`；
再补任务书要求的运行时模块 `jdk.crypto.ec, jdk.charsets, jdk.zipfs, java.management, jdk.localedata`，并加 `java.logging, java.xml` 保险，共 15 个。

**目标说明**：任务书目标 234 MB → ≤120 MB。实测 runtime 从 169 MB 降到 121 MB（约 28%），但 app-image 整体仍为 186 MB。差距在 `lib/app/*.jar`（约 65 MB 的 Compose/Kotlin/HMCL 依赖），这部分**不受 jlink 模块裁剪影响**，且红线要求"不为体积删必需模块"（`java.net.http` 缺失的教训），故未进一步删减。报告如实给出实测值，不为凑数字牺牲必需模块。

## 产物清单与实际可用数据

DSH D3/D5 决议：Win10 VM 验收跳过（用户自验）；appimagetool 只在 CI 的 ubuntu-latest 下载，本机不装。本机为 Linux、无 Windows SFX 模块（`7z -sfx7z.sfx` 在本机报 errno=2）、无 appimagetool，因此 **Windows SFX exe / portable zip / AppImage 均由 GitHub Actions 生成，本机无法产出其字节，故不提供这些产物的本机 sha256**（不伪造）。CI 产物的构建步骤与命名见 `.github/workflows/build.yml`：

| 产物 | CI 构建步骤 | 预期文件名 | 本机 sha256 |
|---|---|---|---|
| Windows SFX | `windows-sfx` 7z `-sfx7z.sfx` | `YMCL-1.0.0-win-x64.exe` | CI 生成，见 Release 的 SHA256SUMS |
| Windows portable | `windows-portable` Compress-Archive | `YMCL-1.0.0-win-x64-portable.zip` | 同上 |
| Linux AppImage | `linux-appimage` appimagetool | `YMCL-1.0.0-x86_64.AppImage` | 同上 |
| SHA256SUMS | 各 job 的 `Get-FileHash`/`sha256sum` + release 合并 | `SHA256SUMS` | 同上 |

## 本机验证（已完成）

1. `./gradlew build`：BUILD SUCCESSFUL（所有单元测试通过，含 P7 的 11 个）。
2. `./gradlew :composeApp:createDistributable`：裁剪版与 allModules 版均成功，体积见上表。
3. **裁剪版 app-image 实跑**：`bin/YMCL -Dymcl.game.root=...` 成功打开 YMCL 启动台窗口，无缺模块异常（验证了 jlink 模块集完整，`java.net.http` 等必需模块均在）。
   - 窗口级截图：`build/p8-appimage-window.png`（1824×2064，窗口 1216×1376 ×1.5），**非全屏 fallback**。
   - PID 断言：`/proc/<pid>/cmdline` 含 YMCL 字样，通过。
   - 截图左上导航有 Y 立方体图标（`Res.drawable.ymcl` 生效；此前用 `painterResource("ymcl.png")` 字符串形式报 `Resource ymcl.png not found`，已改为生成的 `Res` API + `compose.components.resources` 依赖修复）。
4. CI YAML：`yaml.safe_load` 解析通过；7 个 job 齐全；4 必绿 job 存在；console-debug / MSI `continue-on-error: true`；release 仅 v* tag。
   `linux-appimage` 的脚本经 `bash -n` 语法检查通过（heredoc 在 YAML block 中正确顶格）。
5. 版本：`BuildInfo.kt` 由 Gradle task 在每次构建时按 `-Pymcl.version` 和 `git rev-parse --short HEAD` 重新生成（本机上次构建时内容为 `VERSION="1.0.0"`, `SHA="0e03c25"`；CI 构建时会刷新为当下 commit）；`v1.0.0` tag 与 `git describe --match 'v*'` 推导出的 `1.0.0` 一致。

## 本机验证的限制（如实说明）

- **验收 1 的 CI 四 job 全绿**：本机无法执行 GitHub Actions；已做 YAML 解析 + bash 语法检查，真实 CI 运行需在 GH 上推送后确认。
- **验收 2 的产物齐全 / 各产物 sha256**：Windows SFX / portable zip / AppImage 由 CI 生成；本机无对应工具（Linux 7z 无 sfx 模块、无 appimagetool）且 DSH 指示本机不装。产物与 sha256 以 GH Release 的 SHA256SUMS 为准。
- **验收 3 干净 Win10 VM**：DSH D3 决议跳过，用户自验；步骤见 `docs/p8-manual-checklist.md`。
- **验收 5 AppImage 在 WS 跑一次**：本机无 appimagetool、且无 CI artifact 可下载；改以裁剪版 app-image 直接运行验证（见上），AppImage 本体待 CI 产出后从 artifact 下载验证。
- **关于页版本截图**：版本号经生成文件与代码行验证（`:456`、BuildInfo 内容）；本机 app-image 窗口已成功运行，未单独截取关于页（点击导航未命中，非功能问题）。

## 红线遵守

- 未改任何业务逻辑 / 下载器 / UI 布局：仅构建脚本、CI、图标资源、关于页一行版本文本、文档。
- 未为体积删必需模块：`java.net.http`、`jdk.crypto.ec`、`jdk.charsets`、`jdk.zipfs`、`jdk.unsupported`、`java.management`、`jdk.localedata` 全部保留；`java.logging`/`java.xml` 亦保留作保险。
- 截图为窗口级 grim，抓不到窗口即报错，未退化成全屏截图。
