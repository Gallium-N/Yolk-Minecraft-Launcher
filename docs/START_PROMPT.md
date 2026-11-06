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
4. `docs/p2-status.md` — P2 的真实进度与遗留项

## 第一轮只做这些

1. 运行并贴出：
   `pwd`
   `git status --porcelain`
   `git branch --show-current`
   `git log --oneline -5`
   `git tag --list`
2. 确认当前在 `p2-engine` 分支且工作树干净；有未提交改动就停下问我，不要覆盖。
3. 运行 `./gradlew build`，记录 BUILD SUCCESSFUL/FAILED 和测试数量。
   JDK 21 / Gradle 8.14；本机 `gradle.properties` 已固定 JDK 21，换机器时改成
   本机 JDK 21，或让 `JAVA_HOME` 指向它。
4. 用 5–8 行汇报：
   - 构建结果；
   - 当前分支、HEAD、`pre-hy4` 指向；
   - 你理解的 P2 现状；
   - 你认为最该先做的 3 件事（从 HANDOFF 第 4 节里选，不要自行发明）。
5. 汇报完就停，等我选目标；不要自动开始 P2/P3 新功能。

## 现状摘要

- 分支 `p2-engine`，交接 tag `pre-hy4`。
- P2 已跑通：15 个本地版本、3 个系统 Java、Forge 1.20.1 可启动、日志流、
  正常退出、错误 Java 可读提示；19 个测试通过。
- **窗口问题已确认仍在，但暂停**：“窗口放大后界面只画左上角 1280×800”是 P1
  遗留问题，尚未修复；用户因时间暂停。它不是 P2 启动失败，也不要在没有新证据时
  宣称已修好。
- 未收尾：P2 最终验收报告；进程日志 UTF-8→CP936 回退；诊断页左侧仍是 P1 假数据。

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

按 `docs/HANDOFF.md` 第 4 节顺序执行第 2 条（先做 P2 最终验收报告），
因为第 1 条窗口问题当前暂停。
每完成一条，先贴证据（命令输出/截图/测试结果），再问我下一条。

如果我说“修窗口”，再按第 1 条执行；先收集 `hyprctl activewindow -j`、`grim`
截图和触发动作，不要直接猜。

## 汇报格式

- 结论先行；
- 命令输出只贴关键行；
- 明确区分“已验证 / 未验证 / 阻塞”；
- 不要用“应该”“大概”代替证据。

---

## 使用提示

- 想让它直接做 P2 验收报告，把“默认下一步”改成“本轮目标：完成 P2 最终验收报告”。
- 想让它修窗口，把“默认下一步”改成“本轮目标：定位窗口只画左上角问题”，
  并要求先只加诊断、不动业务逻辑。
- 不要把这段和 `prompts/phase2-engine.md` 混用：后者是 P2 原始任务书，里面有
  旧主机路径和当时的需求；本文件才是当前交接状态。
