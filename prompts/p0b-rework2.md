# P0.2 返工合订：点版本没反应 / 没有进度 / 8s 误判

> 一个小 commit（可分两个）：`fix(ui): version row click + explicit progress` / `fix(meta): loader list cache`
> **不要用 QA 钩子当唯一证据**——这次验收必须用真实鼠标点击。

## 现象

真实鼠标点版本行 → 界面没有任何变化：没有选中态、没有 spinner、没有骨架、没有进度条。
它上一轮的解释是"xdotool 点击在 XWayland 下间歇失效，所以连拍用 QA hook 驱动"——
这条要当作**待查的输入问题**，而不是验收的绕过理由。

## 第 1 步：分诊（先加日志，不要先改 UI）

在版本行点击与状态切换两处各打一行日志：

```kotlin
println("[UI] version click id=${entry.id}")        // 在 clickable / selectVersion 入口
println("[UI] step=${state.step} probe=${state.probePhase}")  // 状态变化时
```

然后**用真实鼠标**点一次，看日志：

- **完全没有 `version click`** → 输入没送到 Compose。查：
  - 该行是否真的挂了 `Modifier.clickable`（不是只画了 hover 效果）
  - 行是否被父级 `verticalScroll` / `pointerInput` 吞掉了手势
  - 窗口是否处于可聚焦状态（`Window` 的 `focusable`、点击后 `hyprctl activewindow` 是否真的变成 YMCL）
  - `AnimatedContent` 切换期间是否有一个透明层盖住了列表
- **有 `version click` 但 `step` 没变** → 状态机问题：`selectVersion` 没推进 step，或被重复初始化回退
- **`step` 变了但界面没变** → 渲染问题：LOADER 步的内容在 `AnimatedContent` 里被裁掉/透明/高度为 0

把分诊结论写进报告（一句话 + 日志片段）。

## 第 2 步：把它修到"点一下必然看得见"

无论上面哪条，最终界面必须满足：

1. 点击瞬间：该行背景变化 + 行尾 `CircularProgressIndicator(16.dp)`
2. LOADER 步顶部有**明确的**不确定进度条：`LinearProgressIndicator` + 一行文案
   `正在获取 Fabric 版本…`（3s 后改成 `正在连接 fabric maven…`）
3. 骨架块要有可见对比度（用 `surfaceContainerHighest`，不要比背景只深 2%）
4. 10s 无结果：文案变 `网络较慢，仍在尝试` + 「取消」
5. 30s：错误条 + 「重试」「返回列表」（8s 判定必须删掉——实测 maven 要 12.5s）
6. 「取消」「返回列表」都能真打断等待（沿用 generation 令牌）

## 第 3 步：loader 列表缓存（顺带做掉，否则每次都要等 12s）

- `MetaRepository.loadFabricLoaderVersions(mc)` 先读 `data/meta/fabric/<mc>-loaders.json`（TTL 24h）
- 缓存新鲜 → 直接返回，零网络
- 过期 → **先返回旧缓存**（界面立刻可用）＋后台刷新，刷新失败只记日志
- 无缓存 → 走网络，成功落盘

## 验收（缺一不算过）

1. `build`/`test` 绿；新增 ≥3 单测（缓存新鲜不联网、过期先返回再刷新、刷新失败不影响）
2. **真实鼠标**点一个版本（不用任何 `-Dqa.*`）：贴时间线日志
   `version click` → `step=LOADER` → 3s 文案 → 列表出现；截图四张（点击瞬间/3s/10s/列表）
3. 全程**没有 8s 错误条**
4. 第二次点同一版本：≤300ms 出列表（缓存命中，日志 `loaders cache hit`）
5. 缓存就绪后断源再点同一版本：仍出列表；点未缓存版本：30s 出可读错误
6. 若分诊结论是"输入没送到 Compose"，必须在报告里给出**根因 + 修复点**，
   并额外证明：修复后真实鼠标点击在**新启动的进程**里也生效（不是只在旧会话里）
7. 窗口级截图 + PID 断言，未改 WM_CLASS

## 不要做

改传输层超时参数、改下载器、动 P7/P8、加新的 QA 开关来"绕过真实点击"。
