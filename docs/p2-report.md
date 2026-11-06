# YMCL · P2 最终验收报告

> 依据：`prompts/phase2-engine.md` 验收 1–7（下文逐条对应）
> 日期：2026-11-06 ｜ 分支：`p2-engine` ｜ 基线 tag：`p1-baseline`
> 交接 tag：`pre-hy4`（首版快照，不动）/ `pre-hy4-qa`（问答澄清点）/ `p2-complete`（本报告完成后打）
> 口径澄清来源：`docs/codebuddy-qa.md`、`docs/HANDOFF.md` 第 4 节、`docs/p2-status.md`

---

## 0. 结论与状态标记

**结论：验收 1–7 全部满足，P2 可交接。**

| 状态 | 内容 |
|---|---|
| **已验证（本轮复跑）** | `./gradlew build` / `./gradlew test`；23 个测试；15 个版本目录；`runtimes.json`；日志前 20 行；日志落盘 UTF-8；`pgrep` 无游戏进程；分层纪律 grep；git log/tag/hash |
| **沿用证据（2026-11-05 现场，本轮未复跑）** | Forge 1.20.1 进入主菜单截图；正常退出码 0 / kill 143；错误 Java UI 截图；实例页「15 个实例、首屏 12 行」 |
| **已知限制（范围修正，非 P2 缺口）** | 进程流端到端 CP936 回退留 P7/Windows（见 §验收 1 与 §遗留）；总游玩时长恒 0 |
| **已移交 P5（P2 不处理）** | 诊断页左侧 P1 假数据；P2 只接了右下真实日志面板 |
| **阻塞** | 无 |

---

## 验收 1 · `./gradlew build` / `./gradlew test` 通过

本轮（补完 `decodeLogBytes` 解码器 + 4 个编码单测后）实跑：

```
$ ./gradlew build
TextFilesTest[desktop] > gbk log bytes fall back and keep chinese[desktop] PASSED
TextFilesTest[desktop] > gbk byte stream decodes to chinese[desktop] PASSED
TextFilesTest[desktop] > utf8 log bytes are not mis-decoded as gbk[desktop] PASSED
TextFilesTest[desktop] > utf8 bom in log bytes is stripped[desktop] PASSED
> Task :composeApp:allTests / :composeApp:check / :composeApp:build
BUILD SUCCESSFUL in 3s — 23 actionable tasks: 8 executed, 15 up-to-date

$ ./gradlew test
BUILD SUCCESSFUL in 493ms (UP-TO-DATE)
```

测试报告（`composeApp/build/test-results/desktopTest/TEST-*.xml`）：

```
ConfigCodecTest           tests=3  failures=0 errors=0
InstanceNameValidatorTest tests=5  failures=0 errors=0
JavaRegistryTest          tests=4  failures=0 errors=0
PathUtilTest              tests=3  failures=0 errors=0
TextFilesTest             tests=8  failures=0 errors=0   ← 本次 +4
TOTAL tests=23 failures=0 errors=0
```

任务书要求「P1 的 15 个测试 + 新增 Java 探测/编码测试」：15（P1）+ 4（`JavaRegistryTest` 等）+ 4（本次编码解码）= **23**。

**CP936 范围说明（口径来自 `docs/codebuddy-qa.md` Q1）**：P2 只交付**解码纯函数 + 单测**，不改 HMCLCore 的 pump。

- `expect/actual fun decodeLogBytes(bytes: ByteArray): String`：`Platform.kt:19`、`Platform.desktop.kt:184`；UTF-8 严格解码优先，畸形时回退 GBK/CP936，保留 BOM 剥离。
- `readTextAuto` 改为复用该函数（`Platform.desktop.kt:201`），文件读取行为不变。
- 4 个单测：`TextFilesTest.kt:54/60/66/72`（GBK 字节数组、GBK 字节流、UTF-8 不误判、UTF-8 BOM）。
- **没有**改 `DefaultLauncher`、**没有**改 `vendor/hmcl/` 源码、**没有**重写已验证的 Forge 启动路径。
- 原因：`DefaultLauncher.startMonitors()`（`vendor/hmcl/.../DefaultLauncher.java:1019`）用 `command.encoding`（Linux = `-Dfile.encoding=UTF-8`）建 `StreamPump`（同文件 `:1021/:1026`），字节在 HMCLCore 内已解成 String；`HmclEngine.kt:150` 的 `ProcessListener` 拿到的是 `onLog(String)`，原始字节不可恢复。字节级端到端只有「自管 `ProcessBuilder`（重写启动路径）」或「改 vendor 源码（硬规则禁止）」两条路，故留到 P7/自管进程阶段。**P2 Linux 验收 UTF-8，这是范围修正，不是原始任务书已全量完成。**

---

## 验收 2 · 实例页列出 ≥8 个真实版本

```
$ find ~/Minecraft/versions -mindepth 1 -maxdepth 1 -type d | wc -l
15
```

15 个版本目录（真实本地实例，非伪造）：

```
1.20.1
1.20.1-Fabric 0.19.3
1.20.1-Forge
1.20.4
1.21.1
1.21.1-NeoForge-21.1.248
26.1.2
26.1.2 FO
BBS Mod-3.4.4
CreateTheBrassConcerto
Fabulously Optimized 1.20.1
im
The Broken Script 2.0
The Last Voyage of the Harpy Express - With Costom Roles - FIX
XPlus PerioTable based on Minecraft 1.20.1 (Fabric)
```

- 扫描逻辑 `HmclEngine.listInstances`（`HmclEngine.kt:72-102`）：只读 `versions/*/<id>.json`，不联网、不补全。
- 解析失败会打印 `YMCL versions: skip ...`；**最终启动日志无该行**（DSH 回执 `docs/codebuddy-qa.md` Q3 确认）。
- 实例页显示「15 个实例」，首屏 12 行、其余滚动；证据截图 `build/p2-final-instances.png`、`build/p2-instances.png`。
- 任务书要求 ≥8，实得 **15**，满足。

---

## 验收 3 · `runtimes.json` 注册真实 Java

`~/.local/share/ymcl/data/java/runtimes.json`（本轮实读，带 `schemaVersion`）：

```json
{
    "schemaVersion": 1,
    "runtimes": [
        { "id": "red-hat--inc-25.0.4.1-x64-linux-f8c58cd6", "vendor": "Red Hat, Inc.", "major": 25,
          "version": "25.0.4.1", "arch": "x64", "os": "linux",
          "path": "/usr/lib/jvm/java-25-openjdk/bin/java", "source": "detected", "installedAt": null },
        { "id": "temurin-25.0.5-x64-linux-d66d74b1", "vendor": "Temurin", "major": 25,
          "version": "25.0.5", "arch": "x64", "os": "linux",
          "path": "/usr/lib/jvm/java-25-temurin-jdk/bin/java", "source": "detected", "installedAt": null },
        { "id": "temurin-21.0.13-x64-linux-a2391ff9", "vendor": "Temurin", "major": 21,
          "version": "21.0.13", "arch": "x64", "os": "linux",
          "path": "/usr/lib/jvm/java-21-temurin-jdk/bin/java", "source": "detected", "installedAt": null }
    ]
}
```

- 3 个真实系统运行时，均为 `source: detected`；要求「至少 1 个」，满足。
- `path` 是运行时探测结果（`<java> -XshowSettings:properties -version` 解析），**代码未写死任何本机路径**；探测在 `Platform.desktop.kt:54`，注册表读写在 `JavaRegistry.kt:45`（`readTextAuto` + `ignoreUnknownKeys`，`schemaVersion > 1` 报错）。
- 测试用的临时 Temurin 8 及其注册表项已删除，注册表回到 3 个系统运行时（`docs/p2-status.md` §Open items 5）。

---

## 验收 4 · 启动真实实例并进入主菜单

**实例**：`1.20.1-Forge`（Forge 47.4.23），用 Temurin 21 启动。

日志流前 20 行（`~/.local/share/ymcl/data/logs/1_20_1-Forge-1793892804689.log`，本轮实读）：

```
 1 [23:33:25] [main/INFO]: ModLauncher running: args [--username, YMCL, --version, 1.20.1-Forge,
     --gameDir, ~/Minecraft, --assetsDir, .../assets, --assetIndex, 5, --uuid, ..., --accessToken, ❄❄❄❄❄❄❄❄,
     --userType, msa, --versionType, release, --width, 854, --height, 480, --launchTarget, forgeclient,
     --fml.forgeVersion, 47.4.23, --fml.mcVersion, 1.20.1, --fml.forgeGroup, net.minecraftforge, ...]
 2 [23:33:25] [main/INFO]: ModLauncher 10.0.9+... starting: java version 21.0.13 by Eclipse Adoptium; OS Linux arch amd64
 3 [23:33:25] [main/INFO]: Loading ImmediateWindowProvider fmlearlywindow
 4 [23:33:25] [main/INFO]: Trying GL version 4.6
 5 [23:33:25] [main/INFO]: Requested GL version 4.6 got version 4.6
 6 [23:33:25] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.5 ...
 7 [LWJGL] [ThreadLocalUtil] Unsupported JNI version detected, this may result in a crash.
 8 [23:33:25] [pool-2-thread-1/INFO]: GL info: NVIDIA GeForce RTX 5060 Ti ... GL version 4.6.0 NVIDIA 615.78.08
 9-12 [23:33:25] [main/WARN]: Mod file .../fmlcore|javafmllanguage|lowcodelanguage|mclanguage...
     is missing mods.toml file
13-18 [23:33:26] [main/INFO]: Found mod file ... (fmlcore / javafmllanguage / lowcodelanguage /
     mclanguage / client-1.20.1-...-srg.jar / forge-1.20.1-47.4.23-universal.jar)
19 [23:33:26] [main/INFO]: No dependencies to load found. Skipping!
20 [23:33:26] [main/INFO]: Launching target 'forgeclient' with arguments [--version, 1.20.1-Forge, ...]
```

（含任务书要求的等价标记：`--username YMCL`、`java version 21.0.13`、`[LWJGL]`、`Launching target 'forgeclient'`。）

**其余证据**：

```
# 游戏窗口（grim，沿用 2026-11-05 现场）
build/p2-forge-menu.png       — Forge 1.20.1 主菜单
build/p2-minecraft-menu.png   — 原版 1.20.1 主菜单
build/p2-diagnostics.png      — 启动中实时日志面板

# 退出游戏后（本轮实跑；用 bracket 模式避免 pgrep 自匹配）
$ pgrep -af '[j]ava .*minecraft'
(empty — no game process)
$ ps -p <3 个 java PID> -o args= | grep -ci minecraft
0    # 3 个进程均为 Gradle daemon / Kotlin build tools，非游戏进程

# 日志已落盘且为 UTF-8（本轮实跑）
$ ls -1 ~/.local/share/ymcl/data/logs/
1_20_1-Forge-1793892804689.log   1_20_1-Forge-1793892664365.log
1_20_1-Fabric_0_19_3-1793892588390.log   1_20_1-1793892561887.log
$ file -bi ~/.local/share/ymcl/data/logs/1_20_1-Forge-1793892804689.log
text/plain; charset=utf-8
```

- 落盘编码由 `HmclEngine.kt:140` 的 `logFile.bufferedWriter(StandardCharsets.UTF_8)` 保证。
- 正常「退出游戏」退出码 0；强杀进程为 143 并显示可读卡片（沿用 `docs/p2-status.md`）。
- 进程清理：`HmclEngine.kt:233-253`（`descendants()` → `destroy()`，3 秒后 `destroyForcibly()`）。
- **pgrep 口径**：`pgrep -af "java.*minecraft"` 会匹配命令自身，不可作为「空」的证据；改用 `pgrep -af '[j]ava .*minecraft'`（DSH 回执 Q4 / HANDOFF 第 4 节）。

---

## 验收 5 · 故意用错 Java → UI 可读错误

**口径修正（任务书笔误）**：原验收写「把 `javaId` 指向 Java 17 去启动 1.20.1」，但 1.20.1 本来就要求 Java 17+，用 Java 17 不会报错。实际用**真实 Temurin 8** 触发预检失败；**未伪造 Java 17 失败**（DSH 回执 Q2 / HANDOFF 第 3 节）。

UI 错误文案（沿用现场，`docs/p2-status.md` §What is done）：

```
Java 版本不匹配：1.20.1 需要 Java 17 或更高，当前是 Temurin 1.8.0_512。
```

- 生成处：`HmclEngine.kt:110-115`（`requiredJavaMajor(version)` 预检，`HmclEngine.kt:357-368`：1.20.1 → 17）。
- UI 侧失败分类：`AppState.kt:265-279`（Java 不符 / Mixin 冲突 / 异常退出 粗分类，不是白框）。
- 截图：`build/p2-wrong-java2.png`、`build/p2-wrong-java.png`。
- 测试用 Java 8 是临时放在 `build/jdks/` 的真实 JRE，验证后已连同注册项删除。

---

## 验收 6 · 改动文件:行清单 + HMCLCore 适配类

`git diff --name-only p1-baseline..HEAD` 共 **30 个 YMCL 自有文件 + 626 个 vendored HMCLCore 源文件**（完整清单可用该命令复现）。核心文件:行：

| 文件 | 行 | 作用 |
|---|---|---|
| `composeApp/src/commonMain/.../platform/Platform.kt` | `:19` / `:22` | `expect decodeLogBytes` / `expect readTextAuto` |
| `composeApp/src/commonMain/.../platform/JavaRegistry.kt` | `:45` | `runtimes.json` 读写、`ignoreUnknownKeys`、`schemaVersion` |
| `composeApp/src/commonMain/.../app/ConfigCodec.kt` | `:115` | `readInstance` 经 `readTextAuto` |
| `composeApp/src/commonMain/.../app/AppState.kt` | `:173-222` / `:265-279` | 启动流程、日志流收集、失败可读化 |
| `composeApp/src/commonMain/.../engine/GameEngine.kt` | 全文 | 引擎接口（domain 模型，无 HMCL 类型） |
| `composeApp/src/commonMain/.../engine/FakeGameEngine.kt` | 全文 | P1 假引擎 |
| `composeApp/src/commonMain/.../domain/JavaRuntime.kt` | 全文 | `JavaRuntime` 模型（纯 Kotlin，无 `java.io`） |
| `composeApp/src/commonMain/.../ui/pages/InstancesPage.kt` | 全文 | 真实版本列表接线 |
| `composeApp/src/commonMain/.../ui/pages/DiagnosticsPage.kt` | `:254` | 右下真实日志面板（左侧假数据属 P5） |
| `composeApp/src/commonMain/.../ui/pages/SettingsPage.kt` | 全文 | 游戏目录 + Java 列表 + 关于 HMCL 声明 |
| `composeApp/src/desktopMain/.../engine/HmclEngine.kt` | `:55` `:69` `:72` `:104` `:110-115` `:138-140` `:150-181` `:183-184` `:223` `:233-253` `:255-308` `:310-368` `:376-400` `:403-451` | 类定义 / 探测 / 列版本 / 启动 / Java 预检 / UTF-8 落盘 / `ProcessListener` / `DefaultLauncher.launch()` / 等退出 / 进程树清理 / manifest 解析 / MC 版本·加载器·Java 判定 / 历史 / `HmclGameInstance` |
| `composeApp/src/desktopMain/.../platform/Platform.desktop.kt` | `:54` `:184` `:201` `:207` `:219` | Java 探测 / `decodeLogBytes` / `readTextAuto` / 字节解码 / UTF-8 无 BOM 写入 |
| `composeApp/src/desktopTest/.../TextFilesTest.kt` | `:54/60/66/72` | 4 个新增编码单测 |
| `composeApp/src/desktopTest/.../JavaRegistryTest.kt` | `:41` | 注册表读写单测 |
| `composeApp/build.gradle.kts` / `settings.gradle.kts` / `gradle/libs.versions.toml` | — | 引入 vendored `:hmcl-core` 子项目 |
| `vendor/README.md` / `vendor/hmcl/HMCLCore/build.gradle.kts` | — | 来源 commit、许可证、同步方式（源码副本未改动） |
| `LICENSE` / `README.md` / `AGENTS.md` / `NOTES.md` | — | GPLv3、项目说明、硬规则、设计规则 |
| `docs/hmcl-api-notes.md` / `docs/HANDOFF.md` / `docs/p2-status.md` / `docs/codebuddy-qa.md` | — | API 侦察 / 交接 / 现场快照 / 疑问回执 |

**对 HMCLCore 的适配类**（详见 `docs/hmcl-api-notes.md`；`HmclEngine.kt:25-40` 是全部 import，`commonMain` 零 HMCL 类型）：

| 分组 | 类 |
|---|---|
| Java | `org.jackhuang.hmcl.java.JavaInfo`、`JavaRuntime`（`of(Path, JavaInfo, boolean)`）；`org.jackhuang.hmcl.util.platform.Platform` |
| 实例/清单 | `game.GameInstance`（由 `HmclGameInstance` 实现，`HmclEngine.kt:403`）、`GameInstanceManifest`（`fromJson` / `merge` 反射，`:302`）、`GameInstanceID`、`DefaultGameRepositoryLayout`、`GameComponentAnalyzer`、`AssetIndex`、`GameRepository`（fail-fast stub） |
| 版本 | `util.versioning.GameVersionNumber`（`asGameVersion`） |
| 启动 | `game.LaunchOptions.Builder`、`launch.DefaultLauncher`（`launch(): ManagedProcess`）、`launch.ProcessListener`（`onLog/onExit`）、`auth.AuthInfo`、`util.gson.JsonUtils` |

- **刻意未用** `DefaultGameRepository`：它 import `javafx.application.Platform` / `javafx.beans.property`，会把 JavaFX 图形栈拉进 Compose 桌面应用；该 HMCL revision 也**没有** `JavaManager.java`，等价边界是 `JavaRepository` + `JavaInfo`/`JavaRuntime` 对，P2 只探测系统运行时故不需 `JavaRepository`。
- 构建方案：方案 A（`includeBuild("HMCL")`）因 HMCL 用 Gradle 9.8 buildSrc、与 YMCL Gradle 8.14 二进制不兼容而在配置期失败，保留失败历史后改方案 B（vendor 源码副本）——见 `docs/hmcl-api-notes.md` §Build integration decision。

**分层纪律核对（本轮实跑）**：

```
$ grep -rn "org.jackhuang" composeApp/src/commonMain
(empty)                                   # ui/app/domain/engine 零 HMCL 泄漏
$ grep -rn "import java.io\|import java.nio\|import java.awt" composeApp/src/commonMain/.../domain
(empty)                                   # domain 无 java.io
```

**GPLv3 义务**：`LICENSE`（GPLv3 全文）、`vendor/README.md`（来源 commit `b46a07cd45be326878498b22daa01a79ea3ea1bd`、许可证、同步方式）、设置 → 关于 的 HMCL 版权声明均保留；分发以 YMCL 名字区分。

---

## 验收 7 · Git 证据与回退命令

`git log --oneline p1-baseline..HEAD`（撰写本报告时的输出；本文件提交后顶部新增一行）：

```
1797aa4 (HEAD -> p2-engine) docs: add CodeBuddy Q&A response
5d86b83 (tag: pre-hy4-qa) docs: answer handoff review questions
a5b9fe4 feat(log): add UTF-8/CP936 decoder helper
dd3767b (tag: pre-hy4) docs: mark p2 status as historical snapshot
4175b69 docs: mark window issue resolved after recheck
ae2bdfe docs: clarify paused window issue and add start prompt
1f3c4a9 docs: add handoff and agent rules
0c1a6da chore: remove host-specific paths from fixtures and docs
6bd9948 (tag: p2-checkpoint) docs: save P2 status and resume notes
1477196 (tag: p2-launch-ok, tag: p2-java-ok) feat(ui): wire real versions and launch state
6288b3b feat(engine): HmclEngine launch with log stream
04e4940 feat(java): runtime registry + detection
54c7658 build: vendor HMCLCore module
067ee20 docs: record HMCLCore API recon
3f8670d (main) docs: add P2 engine prompt
```

提交粒度符合任务书第 -1 步最低 5 个里程碑：`docs: record HMCLCore API recon` → `build: vendor HMCLCore module` → `feat(java)` → `feat(engine)` → `feat(ui)`，每个 commit 均可独立 `./gradlew build`。

```
$ git tag --list
p1-baseline
p2-checkpoint
p2-java-ok
p2-launch-ok
pre-hy4
pre-hy4-qa

HEAD        = 1797aa4
pre-hy4     = dd3767b   # 首版交接快照，保持不动
pre-hy4-qa  = 5d86b83   # 问答澄清后的交接点
p2-complete = <本报告提交后单独打>
```

**回退命令三条**（可直接执行，来自 `docs/HANDOFF.md` 第 8 节）：

```bash
git switch main && git branch -D p2-engine       # 丢掉整个 P2
git switch -c retry p1-baseline                  # 从 P1 状态重开一条
git revert <hash>                                # 只撤某一步（不改写历史）
```

---

## 遗留与移交（不计入 P2 验收缺口）

1. **CP936 进程流端到端回退 → P7/Windows**：`decodeLogBytes` 纯函数 + 4 单测已完成；替换 `DefaultLauncher.startMonitors()` 的 pump 需要自管进程或改 vendor 源码，会重写已验证的 Forge 启动路径或违反硬规则。P2 Linux 验收 UTF-8。**范围修正，非未完成项**（`docs/codebuddy-qa.md` Q1、HANDOFF 第 4 节第 2 条）。
2. **诊断页左侧假数据 → P5**：P2 只把右下日志面板接了真实日志流（`DiagnosticsPage.kt:254`）；左侧结论/线索卡仍是 P1 假数据，P5 用真实规则结果替换。HANDOFF 第 4 节第 4 条已标注「P2 交接不处理」（Q5）。
3. **总游玩时长恒 0**：只持久化 `lastLaunchedAt`（`HmclEngine.kt:376-400`，`launch-history.json` 带 `schemaVersion`），未累计 play time。
4. **窗口问题已复测关闭**：2446×1376 单窗口铺满，未复现「只画左上角 1280×800」；低于 1100×700 的裁切是 Hyprland 限制。不主动重开，除非再次报告 2446 级别异常（`docs/HANDOFF.md` §3、§6）。
5. **下一阶段**：P3 下载与安装（HANDOFF 第 4 节第 3 条），不在本报告范围。

---

## 构建/运行复现

```bash
export JAVA_HOME="<JDK 21 home>"   # 本机 gradle.properties 已固定 Temurin 21
./gradlew build
./gradlew test
./gradlew :composeApp:createDistributable
composeApp/build/compose/binaries/main/app/YMCL/bin/YMCL
```

JDK 21 + Gradle Wrapper 8.14；JDK 25 起不来 Gradle 8.14。设计尺寸以 `design/mockups/` 与 `NOTES.md`「响应式规则」为准。
