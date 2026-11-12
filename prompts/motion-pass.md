# 动效 pass：让界面"活"起来（MD3 Expressive 运动系统）

> 任务书只读。只动 `ui/` 与新增 `ui/motion/`，**不改任何布局尺寸、不改数据层**。
> commit 切分：`feat(motion)` token+组件、`feat(ui)` 各页面接入、`test(ui)` + 截图证据。

## 目标

现在的界面是"一次画好、硬切"。要的是 MD3 Expressive 那套：**空间移动用 spring、转场用 shared axis、
按下有回应，但一帧都不许让布局跳动**。

`reduceMotion` 已经存在（`AppState.kt:178`、设置页开关、`Controls.kt:43` 的 snap 逻辑）——
本步的要求是：**所有新动效都必须尊重它**，开了就瞬时完成，不是"慢一点"。

## 1. 先建 token（`ui/motion/Motion.kt`）

```kotlin
object MotionDuration { const val Short1=50; const val Short2=100; const val Medium1=200; const val Medium2=300; const val Long1=450 }
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)
val EmphasizedAccelerate = CubicBezierEasing(0.30f, 0.00f, 0.80f, 0.15f)
fun spatial(reduceMotion: Boolean) = if (reduceMotion) snap() else spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow)
fun effects(reduceMotion: Boolean) = if (reduceMotion) snap() else tween(MotionDuration.Medium1, easing = EmphasizedDecelerate)
```

规则：**位移/尺寸 → `spatial()`；透明度/颜色 → `effects()`**。禁止在业务代码里写字面量时长。

## 2. 七个场景（按这个顺序做，每个都截图）

1. **导航指示器**：选中项的背景胶囊用 `animateDpAsState`/`animateFloatAsState` 从上一项**滑动过去**
   （不是这一项淡入、那一项淡出），形状随宽度 morph
2. **页面切换**：`AnimatedContent` + **shared axis X**——旧页向左淡出 30dp、新页从右淡入 30dp；
   方向随导航索引（往左页走则反向）。前进/后退必须对称
3. **实例卡片**：hover 抬升 2dp + shape 24↔12 morph（已有 spring，保留）；
   按下降为 `scale 0.98` 再弹回，用 `spatial()`；整卡点击区域不变
4. **列表进入**：列表项 fade + 从下方 12dp 滑入，**stagger 20ms**，最多前 8 项；
   刷新时只对新增项做，不要每次刷新全表重播
5. **安装/导入进度**：进度条 `animateFloatAsState(spatial())`；阶段标题用 `AnimatedContent(slideInVertically)`
6. **空状态 / 错误条**：fade + `expandVertically` 进入，`shrinkVertically` 退出
7. **删除/取消**：先播退出动画（fade + 轻微收缩），动画结束后再从数据里移除——不许数据先没了、界面还在

## 3. 硬约束

- **布局不动**：动画只动 `offset` / `alpha` / `scale` / `shadow` / `shape`；不许动画 `padding`、`size`、`weight`
- **不做自动播放**：除首屏一次性 fade 200ms，其它动效只在交互时触发
- **不每帧分配**：动画 lambda 里不许建 List/Map/对象字面量；派生值用 `derivedStateOf` 或 `remember`
- **`reduceMotion` 全覆盖**：七个场景逐个验证，开启后全部瞬时；同时保留 `System.getProperty("ymcl.reduceMotion")` 覆盖以便 QA
- **不许引入动画库**（`compose.motion` 之外的），也不许用 `Animatable` 做本可声明式的场景

## 4. 验收（缺一不算过）

1. `build`/`test` 绿；新增单测 ≥3（`spatial/effects` 在 reduceMotion 下返回 snap、stagger 上限 8、shared axis 方向计算）
2. **中间态证明**：页面切换时用 `grim` 连拍 3 帧（间隔 ~80ms），必须能拍到**两个页面同时可见**的过渡帧；
   贴三张图路径 + 说明哪张是中间态
3. **方向对称**：从「实例」到「设置」和从「设置」到「实例」各拍一次中间帧，方向相反
4. **reduceMotion**：开启后用同样手法连拍 3 帧，**拍不到中间态**（全部是终态），贴证据
5. **无布局跳动**：动画前后 `hyprctl activewindow -j` 的 size 不变；同一页面动画结束后的截图与动画前截图
   逐像素一致（`compare -metric AE a.png b.png null:` 输出 0）
6. **帧率**：在 1280×800 下录 10 秒交互（`wf-recorder` 或连拍），报告有无明显卡顿；
   若用 `wf-recorder`，给文件路径
7. 三个场景的截图：导航指示器滑动中间态、卡片按下态、进度动画中间态
8. 报告：`文件:行`、`git log --oneline`、`git status --porcelain`、上述每条原样输出

## 不要做

改布局/间距/字号、改数据层、改 P3 传输层、加新页面、动画自动播放、引入动画库。
