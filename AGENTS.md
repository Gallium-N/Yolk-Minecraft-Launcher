# AGENTS.md — 给任何 AI 编码代理的硬规则

本仓库是 YMCL（Minecraft 启动器，Kotlin + Compose Multiplatform）。
动手前先读 `docs/HANDOFF.md`、`docs/START_PROMPT.md` 和 `NOTES.md`；
它们比本文件更详细，冲突时以它们为准。

## 开工检查

1. `git status` 干净；不干净先确认是谁的改动，**不要覆盖用户未提交的工作**。
2. `git branch --show-current` 应在 `p2-engine`（或用户指定的新分支），不要在 `main` 上直接开发。
3. 先跑 `./gradlew build` 确认基线是绿的；再开始改。
4. 只改当前任务需要的文件；不要顺手重构、改设计 token、改 `design/` 或 `prompts/`。

## 分层纪律（硬性）

```
ui      Compose；禁止 import org.jackhuang.*
app     AppState/用例；不直接做 java.io
domain  纯 Kotlin；禁止 import java.io / java.nio / java.awt
platform expect/actual；所有宿主差异只在这里
engine  GameEngine 接口；HMCL 类型只允许在 desktopMain 的 HmclEngine
```

- `grep -rn "org.jackhuang" composeApp/src/commonMain` 必须为空。
- 路径内部统一 `/`，只在文件/进程边界用 `PathUtil.toNative`。
- 新增持久化文件必须带 `schemaVersion`，并容忍未知字段。

## 构建与测试

- JDK 21 + Gradle Wrapper 8.14；不要用系统默认 JDK 25（Gradle 8.14 起不来）。
- 每个 commit 前必须：`./gradlew build` 通过；改动业务逻辑时 `./gradlew test` 通过。
- 测试在 `composeApp/src/desktopTest/`；JUnit4 风格；不要引入新测试框架。
- 不引新 UI 库、DI 框架或网络库，除非任务明确要求。

## 提交纪律

- 小步提交，信息用英文：`<type>(<scope>): <what>`（如 `feat(engine): ...`）。
- 每个 commit 必须能独立 `./gradlew build`；不要留半截提交。
- **禁止** `git push --force`、`rebase` 已推送历史、删分支、改写历史、
  `git checkout -- .` 覆盖用户改动。
- 撤销自己的改动用 `git revert <hash>`，或在新分支重做。
- 阶段完成打 tag（P1=`p1-baseline`，P2=`p2-java-ok`/`p2-launch-ok`，交接=`pre-hy4`）。

## 设计与尺寸

- 视觉以 `design/mockups/` 和 `NOTES.md` 的「响应式规则」为准；不要自己发明宽度/列宽。
- 根布局不许写死 `width(1144.dp)`；用 `fillMaxWidth()` + `weight()` + `widthIn(min=...)`。
- 圆角/间距/字阶沿用设计 token；不改颜色、字体、种子色、页面内容。
- 启动器窗口最小 1100×700，基准 1280×800；平铺合成器尺寸由 compositor 决定。

## 窗口/验证禁忌

- 不要用 `rememberWindowState(size = DpSize(1280.dp, 800.dp))`；它会在平铺合成器上
  把已经变大的窗口顶回 1280×800，表现为只画左上角。
- 不要给 `window.contentPane` 设 `BorderLayout`；ComposePanel 会变 0×0 白屏。
- **不要用 `wmctrl`/`xdotool` 做窗口尺寸结论**。用
  `hyprctl clients -j` / `hyprctl activewindow -j` + `grim`。
- 改窗口行为后至少验证 1100×700、1600×1000、2446×1376 三档（或说明为何不能）。
- 2026-11-06 复测：2446×1376 正常，极小尺寸裁切是 Hyprland 限制；不要主动重开窗口问题。

## HMCL / 许可证

- `vendor/hmcl/` 是 HMCLCore 源码副本：**不要改其中 Java/Kotlin 源码**；
  只能改 YMCL 自己的 `build.gradle.kts`。`HMCL/` 是只读参考，不改。
- GPL-3.0-or-later WITH AdditionRef-HMCL：分发修改版必须改名区分、保留版权声明；
  `LICENSE`、`vendor/README.md`、设置→关于 的 HMCL 声明不要删。
- 不把 HMCL 的 jar 直接丢进 libs 当二进制依赖。

## 环境与安全

- 仓库内不写死本机绝对路径；`prompts/` 是历史任务书，含旧主机路径，**不要改**。
- 不把密钥/令牌写进代码；有 `.env` 时加入 `.gitignore`，提供 `.env.example`。
- 不改 `design/`、`prompts/`、`HMCL/`，除非用户明确要求。
- 只允许在 workspace 内做改动；不确定的删除/移动先确认绝对路径。
