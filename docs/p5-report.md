# P5 收尾报告：整合包这条路真正闭环

> 分支 `p5-wrap`，基线 `ab1baf6`。任务书 `prompts/p5-wrapup.md`（只读）。
> 截图 / 原始日志：`build/p5-shots/`（gitignore，未入库）。
> 状态约定：✅ 真机端到端 / ❌ 未跑或不可达（写明原因）/ ⚠️ 代码存在、未真机验证（写清卡在哪）。

## 1. 范围

P5 功能提交（`test(modpack)` 解析器 / 三级 CF 解析 / 安装编排 / 导入向导 / 拖放）此前已在，
本轮只补**三件欠的证据**：Forge 整合包端到端、NeoForge 条件项、拖放真机验证，加一份报告。
不改 P3 传输层、不改 JVM 选择、不动效、不加新功能、不写 `~/Minecraft`。

本轮产物（三个 commit）：
- `composeApp/src/desktopMain/kotlin/dev/mayo/ymcl/ModpackE2ESelfCheck.kt` + `composeApp/build.gradle.kts:207` — 端到端自检入口 `modpackE2ESelfCheck`，QA-only `launchOnly` 复验模式。
- `tools/make-p5-packs.py` — 从 Modrinth API 现场查真实文件生成合成 mrpack（url/sha1/sha512/size 不编）。
- `tools/xdnd-drop.py` — 发真实 XDND v5 拖放事件的 python-xlib 脚本。
- `docs/p5-report.md`（本文件）、`docs/HANDOFF.md` §4/§8、`docs/p3-capability-matrix.md` 一行。

## 2. 两种格式实现要点

**mrpack**（`app/ModpackParser.kt:50,58`）：`modrinth.index.json`，`formatVersion <= 1`（`:60` 拒绝更高版本），
`dependencies.minecraft` + 加载器（`forge`/`neoforge`/`fabric`/`quilt`）→ `LoaderKind`；
`files[]` 带 `path`/`fileSize`/`hashes.sha1`/`downloads`，`env.client/server` 控制跳过纯服务端文件；
不安全路径（绝对路径 / `..`）丢弃。

**CurseForge zip**（`app/ModpackParser.kt:51,113`）：`manifest.json`，`minecraft.version` + `modLoaders`，
`files[]` 只有 `projectID`/`fileID`（无文件名）→ 必须经 `app/CurseForgeFileResolver.kt` 解析真实下载地址。

**导入编排**（`app/ModpackBackendImpl.kt:65`）：三段，任何一段失败都定位得到——
- Phase 1（`:90`）共享原版基座 + 加载器（assets/libraries，不进实例目录）；
- Phase 2（`:103`）`files[]` 下载进 `versions/<id>/`，8 并发，复用 P3 `GameDownloader`（sha1 校验、失败删 `.part` 重下、再失败进 `failures` 并让进程非零退出，不静默跳过）；
- Phase 3（`:163`）overrides（`client-overrides` 优先），合并 `instance.json`（带 `schemaVersion`）。

## 3. CurseForge 三级策略（`app/CurseForgeFileResolver.kt:42,46,52`）

| 级别 | 条件 | 行为 |
|---|---|---|
| 1 `API_KEY` | 配置了 API key | 官方 CurseForge API，`x-api-key` 头（不进 URL） |
| 2 `PROXY` | 无 key + 允许代理 | 公共只读代理 `api.curse.tools`，日志 `via=curse.tools` |
| 3 `MISSING` | 都不可用 | 返回 `null`，调用方列出缺失文件给可读错误，**不猜文件名** |

解析到的文件名经 `ModResolver.safeFilename` 去路径分隔符；CDN 地址由解析出的文件名生成。
纯函数、网络注入 `CfFetcher`，三级均可离线单测。

## 4. 本轮四条验收证据

### 4.1 Forge 整合包端到端 — ✅

合成包 `~/ymcl-test/packs/forge-smoke-test.mrpack`，控制变量：
```json
{ "formatVersion": 1, "game": "minecraft", "versionId": "1.0.0", "name": "Forge Smoke Test",
  "dependencies": { "minecraft": "1.20.1", "forge": "47.2.0" },
  "files": [ { "path": "mods/jei-1.20.1-forge.jar", "fileSize": 4805946,
    "hashes": { "sha1": "1ae408badfe8b57417f465a06fc4d03215dba993", "sha512": "ea5f2b8b…" },
    "env": { "client": "required", "server": "required" },
    "downloads": ["https://cdn.modrinth.com/data/u6dRKJwZ/versions/6E4ps2ER/jei-1.20.1-forge-15.74.0.234.jar"] } ] }
```
JEI forge 文件由 `GET https://api.modrinth.com/v2/project/jei/version?loaders=["forge"]&game_versions=["1.20.1"]`
现场查得（version `15.74.0.234`），sha1 用 `sha1sum` 对下载 jar 校验一致；现场数据留 `*.source.json`。
**没有编造** url / sha1 / size。

导入摘要（真实 Forge 实例 `p5-forge2/versions/Forge-Smoke-Test-2`，11-13 那次成功的真机导入；
`/tmp/p5-forge2.log` 已在跨会话间被清理，故不虚构其 `IMPORT` 行）：
```
{"schemaVersion":1,"id":"Forge-Smoke-Test-2","displayName":"Forge Smoke Test","mcVersion":"1.20.1","loader":{"kind":"forge","version":"47.2.0"},"packSource":"mrpack","packVersion":"1.0.0"}
```
自包含 profile `mainClass=cpw.mods.bootstraplauncher.BootstrapLauncher`、Forge profile
`versions/1.20.1-forge-47.2.0/1.20.1-forge-47.2.0.json`、mods 目录 jei jar 的
`sha1=1ae408badfe8b57417f465a06fc4d03215dba993`（与 Modrinth 一致）。
该文件包已下载到实例目录这一事实，证明 base（`1.20.1`）/ loader（`forge 47.2.0`）/ files 三段在那次导入里都走通了。

最终构建（11-15）又跑了一次**全新**导入留档 `build/p5-shots/p5-forge-e2e.log`：
```
IMPORT start id=Forge-Smoke-Test-3 source=mrpack name=Forge Smoke Test mc=1.20.1 loader=forge 47.2.0 files=1 skipped(server-only)=0 overrides=0 bytes=4805946
P5_IMPORT summary: instance=Forge-Smoke-Test-3 files=1 bytes=4805946 failures=0 warnings=1 cancelled=false
```
注意 `warnings=1`：见下方「失败定位」，这一天的 Forge installer 镜像不可达，实例按原版回退创建，
所以它**不是** Forge 端到端证据；Forge 端到端证据是 11-13 的真实 Forge 实例 + 最终构建的 `launchOnly` 复验。

启动日志三行（`build/p5-shots/p5-forge-launch-final.log`，最终构建 `launchOnly` 复验真实 Forge 实例）：
```
YMCL java selected path=/usr/lib/jvm/java-21-temurin-jdk/bin/java major=21 required=17 mc=1.20.1
[14:31:42] [main/INFO]: Launching target 'forgeclient' with arguments [...]
[14:31:44] [modloading-worker-0/INFO]: Forge mod loading, version 47.2.0, for MC 1.20.1 with MCP 20230612.114412
[14:31:44] [modloading-worker-0/INFO]: MinecraftForge v47.2.0 Initialized
```
`Launching target 'forgeclient'` 与 `47.2.0` 与任务书逐字一致。Java 行的口径见 §6（待 DSH 确认）——
生产实际输出为 `HmclEngine.kt:143` 的 `YMCL java selected path=… major=… required=… mc=…`，
自检入口另打一行 `P5_JAVA_SELECTION path=… major=21 required=17 mc=1.20.1`；**未伪造** `reason=` 行。

截图：`build/p5-shots/p5-import-confirm-final.png`（确认页：`Forge Smoke Test` / `1.20.1 · Forge 47.2.0 · mrpack` /
1 个文件 / 4.6 MB / `开始导入`·`取消`）、`build/p5-shots/p5-forge-menu-final.png`（主菜单，左下
`Forge 47.2.0` / `Minecraft 1.20.1` / `MCP 20230612.114412` / `4 mods loaded`，有 `Mods` 按钮）。

失败定位（本轮真机复现，非"环境问题"带过）：最终构建**全新重导**共两次，都卡在 **Phase 1 的
loader 安装环节**，base 与 files 段正常（`files=1 bytes=4805946 failures=0`）：
- `p5-forge-e2e.log`：Forge installer 两个候选（`bmclapi2.bangbang93.com`、`maven.minecraftforge.net`）
  均 `HttpConnectTimeoutException` → `IMPORT base WARN: Forge 加载器自动安装失败 …（实例按原版创建）`
  → `P5_IMPORT summary … warnings=1`。
- `p5-forge-clean-e2e.log`（11-15 再跑一次，此时 `mirrors.hit.edu.cn/bmclapi` 可达）：
  installer jar 本身下载成功（`installer/… <- bmclapi 3180ms 5973950B`），但 Forge installer
  进程内部 processor 要连 `maven.minecraftforge.net` / `libraries.minecraft.net` / `piston-meta.mojang.com`
  等主机时挂住 → 240s 超时（exit 124）。即失败在 **loader 安装器内部的依赖拉取**，不是 base 或 files 下载。

因此最终构建用 `-Dymcl.p5.launchOnly=Forge-Smoke-Test-2` 复验已装好的真实 Forge 实例，
跳过不可达的 installer 依赖，仍然完整复现三行日志 + 主菜单（11-13 那次导入时这些主机可达、装成了真 Forge）。
自检入口的 marker 也据此收紧为必须同时见到 `Launching target 'forgeclient'` 与版本号 `47.2.0`，
避免原版回退时靠 `Setting user` 误报 `P5_OK`（本轮修正了这个假阳性）。

### 4.2 NeoForge（条件项）— ❌ 网络不可达

任务书探针（本轮多次、含 IPv4/IPv6、最长 60s）：
```
$ curl -sI -m 20 https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml
curl: (28) Connection timed out after 20000 milliseconds      # exit=28，stdout 空
$ curl -4 … / curl -6 …                                      # 同样 timeout
* Trying [2001::b92d:761]:443... / Trying 128.242.245.253:443...   # 连接层即挂住
```
同一时刻对照：`api.modrinth.com` → `200`、`piston-meta.mojang.com` → `200`，
说明不是整体断网，是 `maven.neoforged.net` 单主机不可达。
（本会话早段该主机曾 `HTTP/2 200`，NeoForge 1.21.1 合成包已据此用真实 Modrinth 数据生成；
但当前权威探针为不可达，按任务书「不可达 → ❌」记，**不改代码绕过**。）

另跑了一次有界端到端尝试（`build/p5-shots/p5-neoforge-e2e.log`，120s 超时）：
`P5_PLAN … mc=1.21.1 loader=neoforge@21.1.266` → `IMPORT start … loader=neoforge 21.1.266` →
停在 `P5_PROGRESS 安装游戏版本与加载器 0/1`，日志为 1.21.1 基座库从 bmclapi 逐条下载
（neoforge installer 依赖的 maven 主机不可达）。**定位在 Phase 1 base/loader 段**，
与 §4.1 的失败环节判定方式一致。按任务书条件项，结论为 ❌ 网络不可达，不硬跑、不绕过。

### 4.3 拖放 — ⚠️ 代码存在、未真机验证（导入向导未因真实拖放弹出）

`DesktopMain.kt:149` 的 `DropTarget` 代码在（`override drop()` → `acceptDrop(COPY)` →
`javaFileListFlavor` → 取 `.mrpack/.zip` → `state.openImport(path)`），但**没有**真机拖放能弹出向导的证据。
按任务书三种做法逐一试过：

1. **首选 python-xlib**（`tools/xdnd-drop.py`）——真实事件**确实发出去了**，不是"调用导入函数"：
   ```
   DND_SENT XdndEnter version=5 types=['text/uri-list','text/plain;charset=utf-8','UTF8_STRING']
   DND_SENT XdndPosition root=(…,…) action=XdndActionCopy
   DND_SENT XdndDrop
   DND_RECV XdndStatus accept=0        ← AWT 收到了、明确拒绝
   DND_RECV XdndFinished
   DND_RESULT accepted=False selectionRequests=0 finished=True uri=file:///…/forge-smoke-test.mrpack
   ```
   事件日志留 `build/p5-shots/dnd-ymcl-*-rejected.log`、`dnd-ymcl-grid-rejected.log`。
   为排除"脚本/协议写错"，做了**独立 AWT 对照**：同一显示器、同一脚本、同一事件序列打向一个
   普通 `java.awt.Frame`，得到 `accept=1`、`SelectionRequest text/uri-list`、`XdndFinished`，
   且该 Frame 的 `drop()` 打印 `CONTROL DROP FIRED` + `data=[/…/forge-smoke-test.mrpack]`
   （`build/p5-shots/dnd-control-plain-dropTarget.log` / `dnd-control-plain-app.log`）。
   → 脚本与 XDND 协议正确；拒绝来自 YMCL 的 Compose 窗口。
   单个 drag session 扫过 64 个 `XdndPosition` 坐标、toplevel 与子窗口
   （`Content window` / `sun-awt-X11-XCanvasPeer`）全部 `accept=0`。
   根因定位：rootless XWayland 下 `translate_coords(0,0)→root(-400,-150)`（reparent 造成的
   坐标空间错位），AWT `XDnDDropTargetProtocol.processXdndPosition` 无法把目标窗口解析成
   `XWindow`/embedded drop site，走 `MOUSE_EXITED` → `sendResponse` 强制 `ACTION_NONE` → `accept=0`；
   普通 Frame 无 heavyweight 子画布，故不受影响。

2. **Xephyr 嵌套 X 会话**——做不到：`Xephyr` / `Xvfb` / `Xorg` / `weston` / `cage` / `sway` / `labwc`
   均 `MISSING`，`/nix/store` 无 `xorg-server`，且无 `nix`/`nix-shell` 可安装。

3. **GTK3 真实拖放源 + XTEST 真实鼠标拖拽**（补充尝试）——`Gtk.Window` 上 `drag_source_set`
   + `drag_source_add_uri_targets()`，分别在 Wayland 与 `GDK_BACKEND=x11` 两种后端下发真实
   ButtonPress/增量 Motion/ButtonRelease，`drag-begin` 始终未触发（rootless XWayland 下 XTEST
   合成指针事件无法越过 GTK 拖拽阈值）。另确认 `/usr/bin/dragon` 是 KDE 视频播放器，非 DnD 源工具。

**关于 `fix(dnd)`**：本会话早段曾假设「`object : DropTarget()` 无 listener → AWT 默认
`dragEnter` 设 `ACTION_NONE` → 所有拖拽被拒」，并加过 `dragEnter/dragOver/dropActionChanged`
的 `acceptDrag(COPY)`。随后用**严格对照实验**证伪：一个只重写 `drop()`、完全没有那些 override 的
普通 `DropTarget` 同样得到 `accept=1` + `DROP FIRED` + 正确文件名（`DND-NOFIX` 用例）。
故该假设不成立、补丁无依据，**已回退**，`DesktopMain.kt` 与基线一致、零生产代码改动。
这一回退记录在 `build/p5-shots/dnd-control-{plain,fixed}-*.log`。
按任务书「`fix(dnd)` 或说明」，此处交付的是**说明**，不交无证据的补丁。

### 4.4 CurseForge 真包 — ❌ 未验证（无真包）

- `~/ymcl-test/packs/cf/` 不存在，全盘无手动下载的 CF 整合包 zip。
- 环境：`curl https://curse.tools/` → `curl: (6) Could not resolve host: curse.tools`；
  无 API key 配置（`LauncherSettings` 无存储值）；任务书所述 Cloudflare 403 亦无法越过。
- 代码路径由 3 个离线单测覆盖（`composeApp/src/desktopTest/kotlin/dev/mayo/ymcl/ModpackTest.kt`）：
  `:122 curseforge manifest parses loader and files`、
  `:146 cf three level strategy uses api then proxy then readable error`、
  `:218 cf cdn url comes from a resolved filename`。
- 结论：**代码有单测、无真包证据**，保持 ❌ 未验证，不伪造导入结果。

## 5. 已知限制

- **CF**：无 API key、无手动真包、`curse.tools` DNS 不可达；三级策略仅有离线单测。
- **拖放**：`tools/xdnd-drop.py` 能发真实 XDND 事件且经独立 AWT 对照证明有效，但 YMCL 的
  Compose 窗口在 rootless XWayland 下不接受（§4.3）；Xephyr/嵌套 X 会话与标准 DnD 工具在本机缺失。
  在有正常 X11 会话（非 rootless XWayland）或真实文件管理器拖放的机器上，此路径需要复验。
- **NeoForge**：`maven.neoforged.net` 单主机不可达；1.21.1 基座亦未全量缓存，端到端未闭环。
- **Java 日志口径**：见 §6，未伪造 `reason=` 行。
- 本轮未改 P3 传输层、未动 JVM 选择、未改动效、未加新功能、未写 `~/Minecraft`。

## 6. 待 DSH 确认的验收口径（不自行裁定、不照旧任务书硬做）

1. **`Java selected: … reason=exact match` 日志行**：`prompts/p5-wrapup.md:36` 要求启动日志含该行，
   但 `prompts/p7-java.md:45` 把 `JavaChoice.reason` 定义为 **P7** 交付物
   （格式 `Java selected: <path> (<vendor> <ver>) reason=<reason>`），当前生产未实现；
   `docs/HANDOFF.md` §4 把 P7 列为「延后」，p5 任务书「不要做」明令**不改 JVM 选择逻辑**。
   生产实际输出 `HmclEngine.kt:143` → `YMCL java selected path=… major=… required=… mc=…`
   （本轮真机日志 `major=21 required=17 mc=1.20.1`，含真实选择信息、无 `reason=` 字段）。
   本轮做法：不伪造该行，另两条 `forgeclient` / `47.2.0` 逐字命中。
   请裁定：① 是否接受「生产实际格式 + 语义等价」作为该子项通过；② 还是须等 P7 落地
   `JavaChoice.reason` 再补；③ 若允许仅补一行带 `reason` 的日志（不改选择逻辑），归属哪个阶段。
2. **NeoForge 探针时点**：本会话早段 `curl` 为 `HTTP/2 200`、末段（Hyprland 崩溃恢复后）为
   `exit 28 timeout`。当前以末段权威探针判为「不可达 → ❌」。请确认是否以最终探针为准，
   或需在主机恢复可达时补跑 1.21.1 端到端。
3. **拖放验收等级**：`tools/xdnd-drop.py` 已真发 XDND 事件且经独立 AWT 对照证明工具有效，
   但 YMCL Compose 窗口未弹出向导。按任务书三种做法均试过（含试过方案清单与卡点）标 ⚠️。
   请确认该 ⚠️ + 证据链是否满足本条验收，或需补哪种环境。

> DSH 回执后请先读仓库内更新（`docs/codebuddy-qa.md` / HANDOFF / START_PROMPT / p2-status 等）
> 再据此修订本报告与 §4 措辞；本报告不把无证据项写成「已完成」。
