# YMCL · 修「窗口变大但界面只画在 1280×800」

> 整段复制给 dsh。上一轮的教训：wmctrl/xdotool 只能看到 XWayland 的 X 窗口，
> 不能作为"窗口尺寸是否生效"的证据，这次验证工具已全部换成 hyprctl。

---

# 任务：定位并修复"窗口变大但界面只画在左上角 1280×800"的问题

## 环境与已确认事实（不要再重复验证这些）
- 工作站会话：Hyprland (Wayland)，显示器 HDMI-A-1 3840×2160，scale = 1.5，逻辑分辨率 2560×1440
- JVM GUI 只能走 XWayland（AWT 没有 Wayland 后端），这是正常的，不是待修的问题
- 故障：Hyprland 把窗口平铺到约 2446×1376（逻辑像素），但界面只绘制在左上角约 1280×800
  的逻辑区域，右侧与下方是未绘制的黑色区域
- 已排除：`-Dskiko.renderApi=SOFTWARE` 无改善 → 不是 GPU 表面 / swapchain 问题
- 已确认代码里没有固定容器：`YmclApp` 根是 `Row(fillMaxSize)` + `Box(fillMaxSize).padding(horizontal=24dp)`；
  `DesktopMain` 里只有 `rememberWindowState(size = DpSize(1280.dp, 800.dp))` 和
  `window.minimumSize = Dimension(1100, 700)`，没有 setSize / maximumSize / 固定 size 容器
- 所以不要再论证"窗口是否可以缩放"，也不要重复贴代码结构

## 验证工具（必须遵守，这是上一轮踩过的坑）
**禁止**用 wmctrl / xdotool 判断窗口尺寸是否生效：
- 它们只面向 X11/EWMH，看不到 Hyprland 平铺出来的真实窗口
- `xdotool windowsize` 直接给 X 窗口发 ConfigureRequest，合成器随后会按 tile 规则改回去，
  只读一次输出会得到"已生效"的假象
- 它们的 resize 结果与"界面是否重绘"无关

一律使用：
```bash
# 合成器侧真相（逻辑像素，与 AWT 的 window.size 同单位，可直接比较）
hyprctl activewindow -j | jq '{size, at, xwayland, class, floating}'
hyprctl clients -j | jq '.[] | select((.class // "")|test("ymcl";"i")) | {size, at, xwayland, floating}'

# 窗口截图（按合成器给的几何裁剪）
grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')" /tmp/win.png
```

## 第一步：只加诊断，不改行为
在 `DesktopMain` 的 `Window { }` 内容里加：
```kotlin
LaunchedEffect(window) {
    fun dump(tag: String) {
        println(
            "DBG $tag win=${window.size} content=${window.contentPane.size} " +
            "panel=${window.contentPane.components.firstOrNull()?.size} " +
            "scale=${window.graphicsConfiguration.defaultTransform.scaleX}"
        )
        System.out.flush()
    }
    dump("init")
    window.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentResized(e: java.awt.event.ComponentEvent) = dump("resized")
    })
}
```
在 `YmclApp` 的根 `Row` 外面包一层，打印 Compose 侧拿到的约束：
```kotlin
BoxWithConstraints(Modifier.fillMaxSize()) {
    LaunchedEffect(maxWidth, maxHeight) {
        println("DBG compose max=${maxWidth} x ${maxHeight} density=${LocalDensity.current.density}")
        System.out.flush()
    }
    Row(modifier = Modifier.fillMaxSize().background(colors.surface)) { /* 原有内容 */ }
}
```

然后：跑起来、把窗口平铺到 2446×1376（或直接最大化），把 `hyprctl` 的输出和所有 DBG 行贴出来。

## 第二步：按输出分支修（三选一，不要一次全改）
- **分支 A**：`hyprctl.size` == `DBG win` == 2446×1376，但 `DBG compose max` 只有 1280×800
  → 约束没传下去。检查中间是否隔着 `Crossfade` / `AnimatedContent` / 自定义 `Layout`；
  把页面内容改为直接放在 `BoxWithConstraints` 内，或用 `Modifier.fillMaxSize()` 的 `Box` 承接约束。
- **分支 B**：`hyprctl.size` == 2446×1376，但 `DBG win` 仍为 ~1280×800
  → AWT 侧没收到 resize。改：`window.contentPane.layout = java.awt.BorderLayout()`，
  并在 `componentResized` 里 `window.contentPane.revalidate(); window.repaint()`；
  同时移除固定的 `rememberWindowState(size = ...)`（见下方防御性修改）。
- **分支 C**：三个数都是 1280×800
  → 窗口根本没被平铺（`floating=true` 或被 size hint 钉住）。检查
  `~/.config/hypr/hyprland.lua` 里是否有针对该窗口的 float/size 规则并报告；这种情况
  应用侧不需要改代码，改窗口规则即可。

## 防御性修改（无论哪个分支都加上，都是无害的）
1. `window.contentPane.layout = java.awt.BorderLayout()` + `componentResized` 里
   `revalidate()` / `repaint()`。
2. 初始尺寸不要用 `rememberWindowState(size = DpSize(1280.dp, 800.dp))`（避免状态在重组时把
   尺寸顶回去）：
   ```kotlin
   val state = rememberWindowState()
   LaunchedEffect(Unit) { window.setSize(1280, 800); window.setLocationRelativeTo(null) }
   ```
3. `window.minimumSize = Dimension(1100, 700)` 可以保留，但它必须在 `setSize` 之前设置。

## 验收（三项必须同时给出，缺一不算通过）
1. `./gradlew build` 通过（贴结尾 5 行）。
2. 窗口平铺到 2446×1376 后，贴出：
   - `hyprctl activewindow -j | jq '{size, at, xwayland, floating}'` 的输出
   - 最终的 DBG 行（win / content / compose max）
   - `grim` 截图路径，并说明奶油色内容区域占据的实际像素范围
   → 三者必须一致，且内容铺满窗口（无黑边）
3. 再把窗口拉到 1600×1000 和 1100×700 各截一张图，确认内容跟着重排、无横向滚动条、
   无文字截断。
4. 报告里给出改动的 文件:行 清单。

## 不要做
- 不改配色 / token / 字号 / 圆角 / 页面内容与假数据
- 不引新依赖，不加 wmctrl / xdotool 相关代码
- 不动 `~/src` 下其他目录

## 卡住时
把 `hyprctl` 输出和 DBG 行原样贴出来并停下，不要连续猜着改。
