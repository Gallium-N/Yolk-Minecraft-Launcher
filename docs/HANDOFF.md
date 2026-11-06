# YMCL 交接说明（给下一个 coding agent）

## 1. 一句话定位

YMCL（Yolk Minecraft Launcher）是自用的桌面 Minecraft 启动器：Kotlin + Compose
Multiplatform（JVM desktop），引擎接 HMCLCore；无后端、纯本地，重点是崩溃后能说清原因。
本项目只出 Linux/Windows 桌面 JVM，不做手机端。

技术栈：Kotlin 2.4.21、Compose Multiplatform 1.12.2、Gradle Wrapper 8.14、JDK 21、
kotlinx-serialization 1.12.0、HMCLCore（vendored，GPL-3.0-or-later WITH AdditionRef-HMCL）。

## 2. 五层目录职责

```
composeApp/src/commonMain/kotlin/dev/mayo/ymcl/
├── ui/        Compose 页面、组件、主题；禁止 import org.jackhuang.*
├── app/       AppState（页面状态/启动流程）、ConfigCodec、诊断假数据
├── domain/    纯 Kotlin 模型与规则；禁止 java.io
├── platform/  expect/actual：路径、Java 探测、文件编码、通知、时间格式
└── engine/    GameEngine 接口 + FakeGameEngine；desktopMain 的 HmclEngine 适配 HMCLCore
```

`composeApp/src/desktopMain/` 放 AWT 窗口入口与 HMCLCore 适配；`desktopTest/` 是 JUnit4 测试。

## 3. 已完成到哪一步

| 阶段 | 内容 | 锚点 |
|---|---|---|
| P0 | 四屏稿、亮暗 token、生成脚本 | 设计文件随首个快照提交 |
| P1 | 五层骨架 + 四页 UI + 15 个测试；三档窗口尺寸实测 | tag `p1-baseline`，commit `26e25f1` |
| P2 | vendored HMCLCore、Java 注册表、真实版本列表、启动+日志、UI 接线 | tag `p2-java-ok`、`p2-launch-ok`、`p2-checkpoint`；交接 tag `pre-hy4` / `pre-hy4-qa` |

P2 已验证：15 个本地版本（首屏 12 行、其余滚动；最终启动日志无 `YMCL versions: skip`）；
3 个系统 Java；Forge 1.20.1 进入主菜单；正常退出码 0；错误 Java（Temurin 8）显示
可读错误；23 个测试通过；`./gradlew build` 通过。
详细证据和历史记录见 `docs/p2-status.md`；HMCLCore 侦察见 `docs/hmcl-api-notes.md`。

> **Java 17 口径修正**：原任务书“用 Java 17 启动 1.20.1”是笔误——1.20.1 本来就要求
> Java 17+，不会报错。错误 Java 验收用真实 Temurin 8 触发“需要 Java 17 或更高”，
> 报告按 Java 8 口径写，不伪造 Java 17 失败。

> **pgrep 口径**：`pgrep -af "java.*minecraft"` 会匹配命令自身。验收请用
> `pgrep -af '[j]ava .*minecraft'`（方括号让模式不匹配自己）。

> **窗口复测（2026-11-06）**：2446×1376 下启动器铺满窗口，未复现“只画左上角
> 1280×800”。只有把窗口压到很小（低于 1100×700）时会被 Hyprland 强制裁切；
> 按环境限制处理，**暂定关闭**。不要主动重开；除非用户再次报告 2446 级别的异常。

## 4. 下一步（按顺序，每条有验收）

1. **补 P2 最终验收报告**（`docs/HANDOFF.md` 之外单独写 `docs/p2-report.md`）：
   `./gradlew build`/`test` 尾部、15 个版本列表证据、`runtimes.json`、真实启动截图、
   日志前 20 行、退出后 `pgrep -af '[j]ava .*minecraft'` 为空、错误 Java 截图、
   文件:行清单、`git log/tag`、三条回退命令。
   验收：报告逐条对应 `prompts/phase2-engine.md` 的验收 1–7。
2. **CP936：P2 只做解码器纯函数，端到端留给 P7/Windows。** 已完成
   `decodeLogBytes(bytes)` + 4 个 GBK/UTF-8 单测（总计 23 tests），**没有**改
   pump；`DefaultLauncher.startMonitors()` 仍在 HMCLCore 内用 `command.encoding`
   解码。要字节级端到端回退只有自管进程（会重写已验证启动路径）或改 vendor
   源码（禁止），故留到 P7/自管进程阶段。P2 Linux 验收 UTF-8。
3. **P3 下载与安装**：原版清单、Forge/NeoForge/Fabric/Quilt 安装、下载源切换。
   验收：空目录能装出一个可启动实例，且不覆盖已有实例。
4. **P5 诊断（P2 交接不处理）**：类名→jar 索引、规则证据链、Mixin 冲突；把
   `DiagnosticsPage` 左侧假数据换成真实规则结果。
   验收：拿真实 crash-report 给出结论 + 证据 + 一键处置。

## 5. 构建/测试/运行

```bash
cd <repo>
export JAVA_HOME="<JDK 21 home>"   # Temurin 21；Gradle 8.14 不能跑在 JDK 25 上
./gradlew build
./gradlew test
./gradlew :composeApp:createDistributable
composeApp/build/compose/binaries/main/app/YMCL/bin/YMCL
```

本机 JDK 21 通过 `JAVA_HOME` 或 `~/.gradle/gradle.properties` 指定；
仓库内不写任何绝对路径。
`./gradlew run` 也可开发运行；`build/run-hypr.sh` 是 Hyprland 下启动 app-image 的包装，
日志在 `build/hypr-ymcl.log`。

## 6. 已知坑与禁忌

- **不要再用 `rememberWindowState(size = 1280×800)`**：平铺合成器先给 2446×1376 时，
  重组会把窗口尺寸顶回 1280×800，表现为“只画左上角”。尺寸只在
  `minimumSize` 之后 `window.setSize(1280, 800)` 一次。
- **不要给 `window.contentPane` 设 `BorderLayout`**：ComposePanel 会变成 0×0，
  窗口白屏。只保留 `revalidate()/repaint()`。
- **`wmctrl`/`xdotool` 不能作为窗口尺寸验证工具**；用 `hyprctl clients/activewindow -j`
  + `grim`。xdotool 只可用于点击输入。
- **不要改 HMCL 源码**：`vendor/hmcl/` 是上游源码副本；只允许改 YMCL 自己的
  `build.gradle.kts`。HMCL 仓库 `HMCL/` 是 gitignore 的只读参考。
- **GPLv3 义务**：分发修改版必须用 YMCL 名字区分，保留 HMCL 版权声明；
  `LICENSE`、`vendor/README.md`、设置→关于 已处理，不要删。
- **游戏目录**：本机真实目录是 `~/Minecraft`（`~/.minecraft` 只有空 cache）；
  UI 默认会探测 `~/.minecraft`、`~/Minecraft`、`~/Documents/.minecraft`。
- **日志/注册表**：`<appRoot>/data/java/runtimes.json`、`data/logs/`、
  `data/launch-history.json`；改 schema 必须升 `schemaVersion` 并迁移。
- **绝对路径**：业务代码/文档不要写死本机路径；`prompts/` 是原始任务书，
  含历史主机路径，**不要改**，也不要把它们当项目配置。

## 7. 关键文件索引

- 窗口入口：`composeApp/src/desktopMain/kotlin/dev/mayo/ymcl/DesktopMain.kt`
- 根布局/页面切换：`composeApp/src/commonMain/kotlin/dev/mayo/ymcl/ui/YmclApp.kt`
- 状态与启动流程：`composeApp/src/commonMain/kotlin/dev/mayo/ymcl/app/AppState.kt`
- 引擎接口：`composeApp/src/commonMain/kotlin/dev/mayo/ymcl/engine/GameEngine.kt`
- HMCL 适配：`composeApp/src/desktopMain/kotlin/dev/mayo/ymcl/engine/HmclEngine.kt`
- Java 注册表：`composeApp/src/commonMain/kotlin/dev/mayo/ymcl/platform/JavaRegistry.kt`
- Java 探测：`composeApp/src/desktopMain/kotlin/dev/mayo/ymcl/platform/Platform.desktop.kt`
- 设计规格（尺寸/响应式规则）：`NOTES.md` 的「响应式规则」一节
- 原始任务书：`prompts/phase1-shell.md`、`prompts/phase2-engine.md`
- 设计稿：`design/mockups/`、`design/tokens/`
- 本次交接证据索引：`docs/p2-status.md`
- 冷启动 prompt：`docs/START_PROMPT.md`

## 8. 交接 tag

- `pre-hy4`：首版交接快照（`dd3767b`），保持不动。
- `pre-hy4-qa`：本次问答澄清后的交接点；用 `git rev-parse pre-hy4-qa` 取 hash。
- `p2-complete`：**暂不打**；等 `docs/p2-report.md` 写完后，再单独打。
回退：`git switch main && git branch -D p2-engine` 丢弃 P2；
`git switch -c retry p1-baseline` 从 P1 重开；单步用 `git revert <hash>`。
