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

> P3 v2「不留欠债」已完成：四种加载器生产化、老版本 virtual assets、Java 矩阵、
> 传输层续传 / bmclapi-hash / official-only、基座 role / 真实 modCount / 启动宏、
> 整合包并行导入、新建实例向导。证据与未跑项见 `docs/p3-capability-matrix.md`、
> tag `p3-v2`。P4/P5 亦已完成，不再列为下一步。

1. **P6 诊断**：类名→jar 索引、元数据指纹、规则证据链、Mixin 冲突。
   验收：拿真实 crash-report 给出结论 + 证据 + 一键处置。
2. **P7 Java 运行时安装**（延后）：Adoptium API + TUNA/华为云镜像安装器，
   接进 P2 的注册表。验收：装好 JRE 21 并出现在运行时列表里。
3. **P8 打包分发**：单 exe（自解压）+ AppImage + 签名清单自更新。
   验收：两个平台各一个单文件，朋友双击即用。

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

### P3 已知坑（2026-11-07 实测，别「优化」回去）

- **Forge installer 需要 `launcher_profiles.json`**：否则报
  `There is no Minecraft launcher profile ... you need to run the launcher first!`。
  装前在目标目录写一个最小 `launcher_profiles.json`（含 `profiles.<name>.lastVersionId`）。
- **Forge 必须用自包含 profile**：官方 installer 产出的是继承式（`inheritsFrom: 1.20.1` +
  29 库），HMCL 的 module layer 会把原版 jar 解析两次，撞 JPMS
  `Module minecraft contains package com.mojang.blaze3d.platform`。正确做法（见
  `LoaderSelfCheck.installForge`）：合并原版 88 + Forge 29 = 117 库、合并
  `arguments.game/jvm`、去 `inheritsFrom`、`jar` 指向自身并硬链接原版 jar。
  **不要把自包含改回继承式**。
- **全屏 grim 会拍错窗口**：`grim` 默认全屏（3840×2160），同时开着两个 MC 会拍到别的窗口，
  与历史上 `wmctrl` 类假阳性同源。必须窗口级截图：
  `grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`，
  且截图前断言 `activewindow.class` 是游戏窗口。
- **`${clientid}` / `${auth_xuid}` 宏仍未替换**（P2 遗留）：真实日志可见
  `--clientId, ${clientid}, --xuid, ${auth_xuid}`。1.20.1 + Forge 不读这两个参数，离线启动
  无影响；**正版登录 / Realms 前必须处理**。
- **停滞判据**：30s 无新字节 → `STALL@30s` abort 切候选；总 300s 兜底。慢不等于坏
  （C 组 official 上 538KB 花 196s 是网络烂，不是文件坏），只杀没进展。
- **x-bmclapi-hash**：只在 40 位合法 sha1 且本地已存在同 hash 文件时用于「跳过」，
  绝不替代 manifest/版本 json 的 sha1 校验。第二次安装的「本地已有 → 跳过」日志统一为
  `skip(local, bmclapi-hash=<sha1>)`。

### P3 v2 已知坑（2026-11-10，勿回退）

- **老版本 assets 必须虚拟化**：1.6–1.12 的客户端按原始名（如
  `minecraft/sounds/...`）找资源，不是按 hash。安装时把 `objects/<hh>/<hash>` 复制成
  `assets/virtual/<mcVersion>/<原始名>`（`map_to_resources` 再复制一份到
  `<gameRoot>/resources/`），`instance.json` 记 `assetsVirtual=true`，启动时
  `HmclGameInstance.getActualAssetDirectory` 把 `--assetsDir` 指过去。Mojang 现行的
  1.12 / 1.7.10 index 已不带 `virtual` 字段，所以按 1.6–1.12 版本区间兜底触发。
  **不要删掉这段、也不要把 `--assetsDir` 改回普通 `assets/`**。
- **Forge/NeoForge 自包含 profile 仍是硬要求**：四种加载器统一走
  `LoaderInstaller.install()`；Forge/NeoForge 跑官方 installer 后必须把继承式 profile
  合并成 117 库自包含（去 `inheritsFrom`、`jar` 指向自身并硬链接原版 jar），
  否则 HMCL 的 JPMS module layer 仍会重复解析原版 jar。
- **基座实例 `role: "base"`**：`GameInstaller` 给纯版本号的基座目录写
  `instance.json` 的 `role: base`；`FileInstanceRepository` 过滤它（只隐藏、不删目录，
  其它实例还要继承它的 jar/libraries）。别把基座当普通实例列出来，也别删目录。
- **`${clientid}` / `${auth_xuid}`**：`YmclLauncher` 覆写 `getConfigurations()` 注入
  32 位 hex 与空串，日志里不应再有未替换的 `${`。正版登录 / Realms 前仍要确认
  HMCL 对这两个参数的实际取值语义。

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
- `pre-hy4-qa`：问答澄清后的交接点（`5d86b83`）。
- `p2-complete`：P2 最终验收报告完成点（`57f8d6e`）。
- `hy4-start`：P2 收尾清理完成点（`6a8a997`）——推荐起手点之一；
  `pre-hy4` / `pre-hy4-qa` 是修复前的快照，从它们起手会继承
  `gradle.properties` 的绝对路径问题。
- `p3-start`：**P3 起手点**（P3/P4 任务书与冷启动文档入库后的提交）。
  从 `hy4-start` 直接开工会缺 `prompts/phase3-install.md`，所以 P3 请从 `p3-start` 开新分支。
- `p3-complete`：**P3 最终报告完成点**（`docs/p3-report.md` 入库后的提交；E2E 入口已入库，
  但其 forge 步骤因耗时长被中断，详见 `docs/p3-report.md` §4）。P4 请从 `p3-complete` 开新分支。
- `p3-v2`：**P3「不留欠债」完成点**（四加载器 / virtual assets / Java 矩阵 / 传输补齐 /
  界面三错 / 并行导入 / 新建实例向导；`docs/p3-capability-matrix.md` 入库）。
  P6 请从 `p3-v2` 开新分支。
回退：`git switch main && git branch -D p2-engine` 丢弃 P2；
`git switch -c retry p1-baseline` 从 P1 重开；单步用 `git revert <hash>`。
