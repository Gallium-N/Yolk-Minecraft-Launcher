# P3 彻底完成：不留欠债

> 任务书只读。按 A→F 顺序做，每段一个 commit，每个 commit 独立 `./gradlew build` 通过。
> 最后必须产出 `docs/p3-capability-matrix.md`——一张「能力 → 证据 → 状态」的表，没有证据的能力一律标 ❌。

## 为什么重开 P3

P5 暴露：生产代码里 `LoaderInstaller` 只有 `installFabric`，Forge 只活在自检脚本里；老版本
（1.6–1.12）的 virtual assets 从没实现；主界面还有基座实例、模组数恒为 0 两个错。
这些都不能留给用户去发现。

## A. 加载器四件套（Fabric / Forge / Quilt / NeoForge）

生产代码 `app/LoaderInstaller.kt` 统一入口：

```kotlin
fun install(kind: LoaderKind, mcVersion: String, loaderVersion: String?, instanceId: String): LoaderInstallResult
```

**Fabric**（已有，保持）：meta profile → 重写 URL → 下载 8 库 → `inheritsFrom` + `jar: <mc>`

**Forge**（产品化，把 P3 自检搬进来）：

1. installer 经 `DownloadSource.loaderMaven("net.minecraftforge","forge","<mc>-<forge>",null,"installer")`
2. **写 `<gameRoot>/launcher_profiles.json`**（最小合法 JSON，存在则不覆盖）
3. `java -jar ... --installClient <gameRoot>`，先 `--help` 确认参数还在
4. **自包含 profile**：88 原版 + 29 Forge = 117 库、合并 `arguments.game/jvm`、去 `inheritsFrom`、
   `jar` 指向自己并硬链接共享原版。继承式会撞 JPMS `Module minecraft contains package com.mojang.blaze3d.platform`
5. installer 输出的关键行 + 退出码写进日志

**Quilt**（新，与 Fabric 同构）：`https://meta.quiltmc.org/v3/versions/loader/<mc>` 取 loader 版本，
`.../loader/<mc>/<loader>/profile/json` 取成品 profile；下载器沿用 Fabric 路径，`mainClass` 是
`org.quiltmc.loader.impl.launch.knot.KnotClient`。若该 profile 的库 URL 指向 `maven.quiltmc.org`，
把它加到 `DownloadSource` 的 loader maven 候选里。

**NeoForge**（新，1.20.2+ 才有）：

1. installer：`maven.neoforged.net/releases/net/neoforged/neoforge/<ver>/neoforge-<ver>-installer.jar`
2. 本机实测该域三次 000。实现完整代码，但**先探测**：`curl -sI --max-time 20` → 失败则抛
   「NeoForge 官方源不可达（已尝试 N 次），请检查网络或代理」，不许假装成功
3. 安装参数与 Forge 相同（`--installClient`），profile 也做自包含合并
4. 若探测可达，必须真机装一次 1.20.4 或 1.21.1 并启动

四种 loader 都要进 `LoaderKind` 枚举 + 实例列表的加载器列 + 整合包 `dependencies` 映射
（`fabric-loader` / `forge` / `quilt-loader` / `neoforge`）。

## B. 老版本资产：virtual / map_to_resources

1.6–1.12 的 `assets/indexes/<id>.json` 里：

- `"virtual": true` → 需要把 `objects/<hh>/<hash>` **复制**成 `assets/virtual/<indexId>/<原始路径>`
  （原始路径来自 `objects[].name`，如 `minecraft/sounds/...`）
- `"map_to_resources": true` → 额外复制到 `<gameRoot>/resources/<原始路径>`

启动参数相应改为 `--assetsDir <gameRoot>/assets/virtual/<indexId>`（virtual 时），
`--resourcePackDir` 保持默认。不做这一步，1.7.10 / 1.12.2 进去就是无声音无材质。

实现要点：虚拟化只在安装时做一次，记录在 `instance.json` 的 `assetsVirtual=true`，重装要幂等。

## C. 老版本 Java 矩阵

`mcVersion → 需要的 Java 主版本`：≤1.16 → 8，1.17–1.20.4 → 17，1.20.5+ → 21，未知 → 21。

- 启动前按矩阵挑已注册的 Java；找不到匹配 → 可读错误列出「需要 Java 8，本机只有 17/21」
- 用户手动指定的 Java 优先，不被矩阵覆盖
- 本机现有 3 个 Java（P2 已注册），验收时用 1.12.2 + Java 8 / 1.20.1 + Java 21 各跑一次

## D. 传输层补齐

1. **跨重启续传**：`.part` 文件带 `<size>` 与 `<sha1-前缀>` 校验，重启后从已有字节续（Range），
   前缀不符直接丢弃重下
2. **`x-bmclapi-hash` 生效证明**：同一文件第二次安装必须命中「本地已有 → 跳过」，日志写
   `skip(local, bmclapi-hash=<sha1>)`；不能只在代码里读头
3. **official-only 模式跑通**：设置关掉镜像后，装一次 1.20.1 的 libraries + client jar（assets 可复用），
   零 bmclapi 请求，日志 `source=official` 全程

## E. 界面与数据三个错

1. **基座不当实例**：写基座时 `instance.json` 加 `"role": "base"`；推断：目录名纯版本号 **且** `mods/` 空
   **且** 无 `displayName`。仓库过滤它，计数同步，**不许删目录**
2. **模组数真实**：`InstanceSummary.modCount` 数 `<实例>/mods/*.jar`（忽略 `.disabled`、子目录），
   懒加载不卡首屏；非隔离实例数根 `mods/`
3. **启动宏**：`${clientid}` → 32 位 hex（会话内固定）、`${auth_xuid}` → 空串保留位置，
   日志里 `grep -c '\${'` 为 0

## F. 整合包导入并行化

`files[]` 顺序 → 并发 8，复用 P3 并发语义，**不动** `HttpDownloadTransport` 的超时/熔断/失败清单。
FO（52 文件 / 27MB）目标 **≤ 2 分钟**。

## 单测（≥12）

loader：四种 kind 的 URL 与 profile 形态（假 fetcher）、Forge launcher_profiles 补写、117 库自包含；
virtual assets 映射（含姓名含空格/中文）；Java 矩阵边界（1.16.5→8、1.17→17、1.20.4→17、1.20.5→21）；
`.part` 前缀不符重下；基座判定；modCount；宏格式；并发 8。

## 验收（缺一不算过，逐条贴原样输出）

1. `build`/`test` 绿；新增 ≥12；三项 grep 空
2. **Fabric**：1.20.1 装上启动（回归，不许退化）
3. **Forge**：1.20.1 + 47.2.0 真机装上启动，日志有 `Launching target 'forgeclient'`
4. **Quilt**：1.20.1 真机装上启动，日志有 Quilt loader 版本行
5. **NeoForge**：贴 `curl -sI` 探测结果；可达则真机装上启动，不可达则贴可读错误与重试次数
6. **老版本**：1.12.2 真机装（virtual assets）→ `assets/virtual/1.12.2` 文件数 > 0 → 启动进主菜单，
   声音/材质正常（截图 + 日志 `Setting user` 行）；1.7.10 可选
7. **Java 矩阵**：1.12.2 用 Java 8 启动、1.20.1 用 Java 21 启动，日志打印选中的 Java 路径与主版本
8. **续传**：装到一半 `kill`，重启后从已有字节继续（日志 `resume from <n> bytes`），最终 sha1 正确
9. **bmclapi-hash 跳过**：第二次安装日志出现 `skip(local, bmclapi-hash=...)`
10. **official-only**：关镜像装一次，日志零 bmclapi
11. **界面**：实例列表无基座行；FO 显示 `49 个模组`（截图）
12. **并行导入**：FO 新实例 ≤ 2 分钟，日志时间戳并发
13. `docs/p3-capability-matrix.md`：能力 / 证据 / 状态三列，覆盖 A–F 全部条目；没跑过的写 ❌ 并说明原因
14. `docs/HANDOFF.md`：§4 下一步只剩 P6 诊断 / P7 Java 安装 / P8 打包；已知坑补 virtual assets、
   自包含 profile、基座 role；§8 补 tag `p3-v2`
15. 报告：`文件:行`、`git log --oneline 6383f74..HEAD`、`git status --porcelain`

## 不要做

CurseForge 浏览、资源包/光影界面、新建空实例向导、删除实例、
P3 传输层的**参数**（停滞 30s / 总 300s / 熔断阈值）改动、对 `~/Minecraft` 的写入。
