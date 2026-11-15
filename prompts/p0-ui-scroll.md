# P0 修：窗口滚动 / 诊断页占位（先做，30 分钟）

> 任务书只读。一个小 commit：`fix(ui): stop whole-page scrolling at narrow sizes, mark diagnostics as WIP`

## 1. 主界面不该整页滚动

Windows 实测：部分窗口尺寸下，主界面能用滚轮上下滑。原因是页面外层用了
`Modifier.verticalScroll(...)`（`InstancesPage.kt:183`、`GetContentPage.kt:133/375`、
`DiagnosticsPage.kt:307`、`ImportPage` 同类），而内容高度超过可视高度时就整体滚动，
把 Hero 卡和导航一起推走。

规则：

- **页面根容器不滚动**。`YmclApp` 的内容区只有 `Box` + `padding`，不加 `verticalScroll`
- 会溢出的部分自己滚：实例表、搜索结果列表、日志区——用 `LazyColumn` 或带
  `heightIn(max = …)` 的容器内滚动
- 高度用弹性约束：卡片/分区用 `weight()`、`fillMaxHeight(fraction)`、`heightIn(min=, max=)`，
  不许写死 dp 让内容把页面顶出边界
- 三档尺寸（1100×800 / 1280×800 / 1600×800）+ 最小 1100×700 都必须在**一屏内**放下
  启动台；放不下时只能让「最近实例」列表内部滚

## 2. 诊断页改成明确的"开发中"

P6 顺延（见 HANDOFF），但现在诊断页显示 P1 的假数据——比空着更糟。改成：

- 一个居中空状态：`诊断功能开发中` + 一句说明（"崩溃归因、Mod 冲突定位会在后续版本提供"）
- 保留导航入口，不要灰掉（用户需要知道这东西存在）
- 删掉页面里的假数据常量，别再有人误以为是真的

## 3. 验收

1. `build`/`test` 绿；一个 commit
2. 启动台在 1100×800 与 1100×700 下：滚轮滚 10 格，截图前后 `compare -metric AE` = **0**
3. 实例页：滚轮只在实例表内部滚动，左侧导航与工具条不动（贴前后截图）
4. 获取内容页：搜索结果列表内部滚动，页头与实例选择器固定
5. 诊断页：显示"开发中"空状态（截图）
6. `grep -rn "verticalScroll" composeApp/src/commonMain --include=*.kt` 的每一处都要能说明
   "这是列表/日志内部滚动"，不能出现在页面根容器上
