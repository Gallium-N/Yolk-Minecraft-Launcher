# YMCL 冷启动 prompt

把下面整段复制给新的 coding agent（WorkBuddy / 混元 Hy4 或其他）。  
不要先改代码；第一轮只做核对与汇报。

---

你是接手 YMCL 的资深 Kotlin / Compose Multiplatform 桌面工程师。
先不要写业务代码，也不要一上来大改。按顺序做只读核对，然后等我指定本轮目标。

## 项目

YMCL（Yolk Minecraft Launcher）：Kotlin + Compose Multiplatform 桌面 Minecraft 启动器，
引擎接 HMCLCore；无后端、纯本地。当前工作区就是仓库根目录，用 `pwd` 确认。

## 必读（只读，不要改）

1. `docs/HANDOFF.md` — 交接说明、下一步、命令、坑、文件索引
2. `AGENTS.md` — 仓库硬规则
3. `NOTES.md` — 产品/设计/响应式规则
4. `docs/p2-report.md` — P2 最终验收（已通过）；`docs/p2-status.md` 是过程快照
5. `prompts/phase3-install.md` — 当前阶段任务书（v1.1）

## 第一轮只做这些

1. 运行并贴出：
   `pwd`
   `git status --porcelain`
   `git branch --show-current`
   `git log --oneline -5`
   `git tag --list`
2. 确认 HEAD 是 `p3-start` 或其后的提交，且工作树干净；有未提交改动就停下问我，不要覆盖。
3. 运行 `./gradlew build`，记录 BUILD SUCCESSFUL/FAILED 和测试数量。
   JDK 21 / Gradle 8.14；本机 JDK 21 通过 `JAVA_HOME` 或
   `~/.gradle/gradle.properties` 指定，仓库内不写任何绝对路径。
4. 用 5–8 行汇报：
   - 构建结果；
   - 当前分支、HEAD、`hy4-start` 指向；
   - 你理解的 P2 现状与 P3 起点；
   - 你认为下一阶段的第一步（`docs/HANDOFF.md` 第 4 节现在只有 P3、P5 两条，不要自行发明）。
5. 汇报完就停，等我选目标；不要自动开始 P2/P3 新功能。

## 现状摘要

- 分支 `p2-cleanup`；下一个功能阶段从 `p3-start` 开新分支 `p3-install`（`p3-start` 含 P3 任务书）。
- P2 已跑通：15 个本地版本、3 个系统 Java、Forge 1.20.1 可启动、日志流、
  正常退出、错误 Java 可读提示；23 个测试通过。
- **窗口问题已复测通过，暂定关闭**：2446×1376 下单窗口铺满，未复现“只画左上角
  1280×800”。只有窗口压到很小（低于 1100×700）时会被 Hyprland 强制裁切，
  按环境限制处理。不要主动重开；除非用户再次报告 2446 级别异常。
- P2 最终验收报告已完成（`docs/p2-report.md`，含 6 条遗留项）；诊断页左侧假数据留给 P6。
- CP936 解码器纯函数 + 单测已完成；端到端回退留给后续自管进程 / Windows 中文 locale 阶段。

## 硬规则

- 不改 HMCL 源码；`vendor/hmcl/` 只读，`HMCL/` 只读参考。
- 不改 `design/`、`prompts/`。
- `ui` 层禁止 `import org.jackhuang.*`；`domain` 禁止 `java.io`。
- 小步提交，每个 commit 必须能 `./gradlew build`；禁止 force push / rebase 已推送历史。
- 设计尺寸以 `design/mockups` 和 `NOTES.md` 的「响应式规则」为准，不要自己发明。
- GPLv3：保留 `LICENSE`、`vendor/README.md`、设置→关于 的 HMCL 声明。
- 不写死本机绝对路径；`prompts/` 里的旧路径是历史任务书，不要改。
- 验证窗口只用 `hyprctl` + `grim`；不要用 `wmctrl`/`xdotool` 做尺寸结论。

## 默认下一步（只有我说“继续”才做）

按 `docs/HANDOFF.md` 第 4 节顺序执行第 1 条：**P3 下载与安装**，
任务书是 `prompts/phase3-install.md`（v1.1 —— BMCLAPI 默认、可关），先做第 0 步侦察并汇报，
不要直接写代码。
每完成一条，先贴证据（命令输出/截图/测试结果），再问我下一条。

只有我再次明确报告窗口异常时，才新开窗口诊断；先收集 `hyprctl activewindow -j`、
`grim` 截图和触发动作，不要直接猜。

## 汇报格式

- 结论先行；
- 命令输出只贴关键行；
- 明确区分“已验证 / 未验证 / 阻塞”；
- 不要用“应该”“大概”代替证据。

---

## 使用提示

- 想让它直接开工 P3，把「默认下一步」改成「本轮目标：完成 `prompts/phase3-install.md` 的
  第 0 步侦察并汇报」。窗口问题已暂定关闭，不要再主动开窗口任务；只有用户再次报告异常时才新开。
- `prompts/` 下的任务书是**只读输入**：`phase3-install.md` 是当前阶段（v1.1），
  `phase4-resources.md` 是下一阶段；不要改它们，也不要拿旧版记忆替代。
