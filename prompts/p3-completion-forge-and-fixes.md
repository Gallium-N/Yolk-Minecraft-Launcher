# P3 完善：Forge 加载器产品化 + 三个界面/数据欠账

> 任务书只读。commit 切分：`feat(loader)`、`fix(instances)`、`feat(import)`、`fix(launch)`、`test`。
> 每个 commit 独立 `./gradlew build` 通过。

## 背景

P5 暴露出 P3 的欠账：生产代码里 `LoaderInstaller` **只有 `installFabric`**，Forge 只在 P3 的自检脚本里
跑通过，没产品化——所以任何 Forge / NeoForge 整合包导进来都启动不了。

另外主界面有两个错：基座版本被当成实例、模组数量恒为 0。这两条必须修，否则用户第一眼就是错的。

## 1. Forge 加载器产品化（最重要）

把 P3 `LoaderSelfCheck.kt` 里验证过的流程搬进 `app/LoaderInstaller.kt`：

```kotlin
fun installForge(mcVersion: String, forgeVersion: String, instanceId: String): LoaderInstallResult
```

步骤（顺序不能变，每一步都有 P3 踩过的坑）：

1. installer jar 经 `DownloadSource.loaderMaven("net.minecraftforge","forge","<mc>-<forge>",null,"installer")` 下载
2. **写 `<gameRoot>/launcher_profiles.json`**（最小合法 JSON，已存在则不覆盖）——否则 installer 报
   `There is no Minecraft launcher profile`
3. `java -jar forge-installer.jar --installClient <gameRoot>`，先 `--help` 确认参数仍存在
4. **自包含 profile**：把 installer 生成的 `versions/<mc>-forge-<v>/<...>.json` 合并成
   「原版 88 库 + Forge 29 库 = 117 库 + 合并 arguments.game/jvm、去掉 inheritsFrom、`jar` 指向自己」。
   **不要**保留继承式 profile——会撞 JPMS `Module minecraft contains package com.mojang.blaze3d.platform`
5. `jar` 用硬链接指向共享原版 jar（跨设备回退复制）
6. 失败要给可读错误（installer 输出的关键行 + 日志路径）

NeoForge：`maven.neoforged.net` 实测不可达。只加接口与「暂不可用」可读错误，**不实现**。

## 2. 基座版本不能当实例显示

`versions/1.20.1/` 是为整合包准备的原版基座。给它标记并过滤：

- 写基座时在 `instance.json` 里加 `"role": "base"`（没有 `instance.json` 的旧目录按规则推断）
- 推断规则：目录名是纯版本号（`^[0-9]+(\.[0-9]+)*$` 或 `^\d{2}w\d{2}[a-z]$`）**且** `mods/` 为空
  **且** `instance.json` 里没有 `displayName` → 视为基座
- `FileInstanceRepository` 过滤掉 `role == base`；`AppState` 的实例计数同步
- **不要**删目录；基座被整合包引用，删了要重下 740MB

## 3. 模组数量真实统计

实例列表「模组」列现在恒为 0。改为：

- `InstanceSummary.modCount`：数 `<实例>/mods/*.jar`（非递归、忽略 `.disabled`、忽略 `mods/.index`）
- 懒加载：列表先渲染，数量在后台上算好再更新（不许因为数 49 个文件卡住首屏）
- 非隔离实例数 `<gameRoot>/mods/*.jar`

## 4. 整合包下载并行化

`files[]` 现在是顺序下载（27MB 的包约 10 分钟）。改成并发 8：

- 复用 P3 已有的并发语义（信号量 / 固定线程池），**不要**改 `HttpDownloadTransport` 的超时与熔断
- 每个文件仍走候选列表 + 停滞 30s + 总 300s + 失败清单
- 进度按「已完成文件数 / 总文件数 + 字节」推进
- 日志每文件一行 `<path> <- <source> <耗时> <字节>`，并发不串行化日志

目标：Fabulously Optimized（52 文件 / 27MB）导入 **≤ 2 分钟**（原先 ~10 分钟）。

## 5. 启动宏替换（P2 遗留）

启动参数里的 `${clientid}` / `${auth_xuid}` 一直没替换（日志里可见）。离线模式取值：

- `${clientid}` → 32 位 hex（会话内固定，即 `UUID.randomUUID()` 去横线）
- `${auth_xuid}` → 空字符串（离线没有 XUID），但要保留参数位置
- 正版登录后由 auth 模块填入真值——本轮**不做**登录，只保证不出现 `${...}` 字面量

验收：启动日志里 `--clientId <32hex>`、`--xuid ""`，`grep -c '\${' <日志>` 为 0。

## 单测（≥7）

1. `installForge` 在 installer 缺 `launcher_profiles.json` 时自动补写
2. 自包含合并：117 库、无 `inheritsFrom`、`jar` 指向自身
3. 基座判定：纯版本号 + 空 mods → base；纯版本号 + 有 displayName → 不是 base
4. `modCount` 统计：忽略 `.disabled` 与子目录
5. 非隔离实例从根 mods 数
6. 宏替换：离线值格式正确、无 `${` 残留
7. 并发下载：8 个假文件全部完成且失败清单为空

## 验收（缺一不算过）

1. `build`/`test` 绿，新增 ≥7；三项 grep 仍为空
2. **Forge 整合包真机启动**：用合成 mrpack（`dependencies` 里 `minecraft:1.20.1` + `forge:47.2.0`，
   `files[]` 放 1 个 Forge 版 JEI）→ 导入 → 启动进主菜单，日志有
   `Launching target 'forgeclient'` 与 Forge 版本行；贴截图
3. **基座隐藏**：导入后实例列表只有整合包实例，没有「1.20.1 原版」行；截图对比
4. **模组数正确**：Fabulously Optimized 行显示 `49 个模组`（不是 0）；截图
5. **并行导入**：FO 重新导入（新实例名）→ 墙钟 ≤ 2 分钟，日志能看出并发（时间戳重叠）
6. **宏替换**：启动日志 `--clientId` 是 32 位 hex、`--xuid ""`、无 `${`
7. 报告：`文件:行`、`git log --oneline c9043b4..HEAD` 后续段、`git status --porcelain`、上述每条的原样输出

## 不要做

CurseForge 浏览、NeoForge 实现、资源包/光影、新建空实例向导、删除实例、
P3 传输层（超时/熔断/重试）改动、对 `~/Minecraft` 的写入。
