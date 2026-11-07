# P4 第 1 步：UI 接真实实例（能看到、能启动、能打开文件夹）

> 任务书只读。分三个 commit：`feat(instances)` 数据层接线、`feat(ui)` 界面接线、`test(instances)`。
> 每个 commit 独立 `./gradlew build` 通过。

## 目标

P1 的界面到现在还在显示假数据。这一步把它接上真实目录：读 `<gameRoot>/versions/*`，
渲染实例列表，让「启动」和「打开文件夹」真的能用。

**不做**资源下载界面、不做新建实例向导、不做删除实例（危险操作，单独一步再谈）。

## 分层要求（这条最容易被违反）

```
ui/       ← 只依赖 app 层接口，禁止出现 org.jackhuang、禁止直接读文件系统
app/      ← InstanceRepository 接口 + InstanceSummary 模型 + 用例
platform/ ← 实现 InstanceRepository：读 versions/、解析 json
engine/   ← 启动仍走 GameEngine，UI 不碰
```

- `app/InstanceRepository.kt`（新）：`interface InstanceRepository { fun list(): List<InstanceSummary> }`
- `app/InstanceSummary.kt`（新）：`id`、`displayName`、`mcVersion`、`loaderKind`（`vanilla|fabric|forge|quilt|neoforge`）、
  `loaderVersion: String?`、`isolated: Boolean`、`path`（仅用于显示/打开文件夹，UI 不许拿它做 IO）
- 实现放 `platform/FileInstanceRepository.kt`：

| 来源 | 取值 |
|---|---|
| `versions/<id>/instance.json` | `displayName`（缺省用目录名） |
| `versions/<id>/<id>.json` | `inheritsFrom` / `mainClass` / libraries 推断 loader |
| 目录名 `fabric-loader-<loader>-<mc>` | 解析出 loader 与 mc 版本 |
| `<gameRoot>/mods/` 非空 | 该实例 `isolated = false` |

- loader 推断顺序：`instance.json` 明确写明 → 版本 json 的 `mainClass`（`KnotClient`=fabric、
  `BootstrapLauncher`=forge/neoforge）→ 目录名模式 → 兜底 `vanilla`
- 解析失败的单条实例**不要**让整张列表崩：跳过并在 `list()` 的错误回调里记一条（UI 显示"1 个实例读取失败"）

## 界面要求

- **实例页**：用真实列表替换假数据，沿用 P1 的六列表格与 92dp 行高；
  `状态` 列显示 `就绪 / 运行中 / 读取失败`
- **启动台**：Hero 卡取"最近启动"的实例（没有记录则取列表第一个）；
  「最近实例」取前 4 个；「所有实例」条显示真实总数
- **空状态**：0 个实例时显示"还没有实例"，并给一个按钮指向"获取内容"页面（占位，P4-step2 接）
- **动作**：
  - 「启动」→ 后台线程调用 engine，按钮进入 loading，成功后在状态列显示"运行中"，
    进程退出后 1 秒内回到"就绪"；失败弹可读错误（含日志文件路径）
  - 「打开文件夹」→ 用 platform 的 openFolder 打开 `versions/<id>/`
  - 三点菜单：只放「打开文件夹」「复制路径」，**不要**放删除
- **刷新**：列表页工具条加一个刷新按钮；窗口重新获得焦点时自动刷新（`WindowState.isMinimized` 反转或 focus listener）
- 启动时**必须**把 `LauncherSettings.isolatedInstances` 传进 `LaunchRequest.isolated`
  （P4-step0 遗留的那根线，这次接上）

## 单测（≥5，不联网、不启进程）

1. `versions/` 里 vanilla + fabric + forge 三个目录 → 三个 summary，loader 推断正确
2. `fabric-loader-0.20.1-1.20.1` 目录名解析出 loader=0.20.1、mc=1.20.1
3. `instance.json` 有 displayName 时优先用它，缺省回落目录名
4. `<gameRoot>/mods/` 非空 → `isolated=false`
5. 某个版本 json 是坏 JSON → 跳过该条，其余照常返回（不抛）

## 验收（缺一不算过）

1. `build` / `test` 通过；新增单测 ≥5；`grep -rn "org.jackhuang" composeApp/src/commonMain` 为空
2. **对着 P4-step0 的产物启动真界面**：`gameRoot` 指向 `~/ymcl-test/p4-accept` →
   列表显示 `Vanilla-One`、`Vanilla-Two`、`Fabric-Lab` 三个实例，
   后者的 loader 列显示 `Fabric 0.20.1`，前两个显示原版 1.20.1
3. 点「启动」Vanilla-One → 游戏真的起来进主菜单，界面状态变「运行中」，退出后回「就绪」；
   贴 UI 日志与游戏日志的时间戳对齐
4. 点「打开文件夹」→ 打开的是 `versions/Vanilla-One/`（贴文件管理器截图或 `xdg-open` 日志）
5. **三档窗口尺寸各一张窗口级截图**：1100×800 / 1280×800 / 1600×800，
   用 `grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`，
   截图前断言 `activewindow.class` 是 YMCL（不是 Minecraft），并且窗口尺寸与 `hyprctl` 报告一致
6. 空状态：`gameRoot` 指向一个空目录 → 显示"还没有实例"，不崩、不白屏
7. 报告：`文件:行`、`git log --oneline 3436b65..HEAD`、`git status --porcelain`、三张截图路径

## 先做的一件事（step0 的收尾缺口）

step0 的"第二次装 0 下载"是在**已预热**的共享根上验的，冷路径没重放。开工前补一个 3 分钟的小测试：

```bash
# 全新共享根，只把 assets 硬链接进去（模拟用户已有资源），libraries 与 client jar 走真实下载
mkdir -p ~/ymcl-test/p4-cold2 && cp -al ~/ymcl-test/mc/assets ~/ymcl-test/p4-cold2/assets
# 然后在这个根上装 1.20.1，期望：libraries ~89M + client jar 22M 真实下载，assets 全 skipped
```

期望：`downloaded` 数量 ≈ libraries 文件数 + 1，`skipped` ≈ 3575，耗时 ≤ 3 分钟，
启动进主菜单。这条过了，才能说冷路径在新布局下也成立。

## 不要做

Modrinth / CurseForge、新建实例向导、删除实例、NeoForge、整合包、
对 `~/Minecraft` 的写入、P3 下载器逻辑的任何改动。
