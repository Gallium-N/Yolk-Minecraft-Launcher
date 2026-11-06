# YMCL · P3 提示词 v1.1：版本清单 + 下载 + 加载器安装

> 整段复制给编码代理。
> **v1.1 变更（用户 2026-11-06 指示）**：① BMCLAPI 改为**默认**下载源，可在设置里关闭；
> ② 允许从 `~/Minecraft` **复制**一个版本出来当测试素材（仍禁止写入原目录）。
> 前置：P2 完成、`hy4-start` 已打（若未打，先跑 `prompts/p2-cleanup.md`）。
> 本阶段只做"从空目录装出可启动实例"，不做 Mod 市场（P4）、整合包（P5）、诊断（P6）。

---

# 任务：P3 — 原版版本清单 / 下载 / 加载器安装

## 背景与已确认事实
- YMCL：Kotlin + Compose MP 桌面启动器，`~/src/ymcl`，五层结构 + vendored HMCLCore（**禁止改 vendor 源码**）
- P2 已能：列本地版本、用系统 Java 启动、抓日志流、错误可读化
- 真实游戏目录 `~/Minecraft` 有 15 个实例：**可以复制出去当测试素材，但不许改动原目录**
  （例：`cp -a ~/Minecraft/versions/1.20.1 ~/ymcl-test/mc/versions/`；复制前后比对 sha1 证明源未变）
- 网络现实：GitHub 本机不稳定/常不可达；Mojang / Fabric maven / Maven Central 通常可达；
  **BMCLAPI 在国内可用，作为默认源**
- 硬规则（见 AGENTS.md）：UI 层禁止 `import org.jackhuang.*`；domain 禁 `java.io`；不改 HMCL 源码

## 第 0 步：侦察（只读，先出报告再写代码）
**已知事实（已核对 vendor 源码，别再去猜）**：HMCL 的 core **没有**全套 BMCLAPI 路由，只有两处：
- `util/Constants.java:16` `DEFAULT_VERSION_DOWNLOAD_URL = "https://bmclapi2.bangbang93.com/versions/"`
  （即版本 jar 走 BMCLAPI）
- `task/FetchTask.java:313-341` 读取响应头 `x-bmclapi-hash`（BMCLAPI 返回真实 sha1 时用它复用本地缓存，
  省一次重复下载）
`maven/`、`assets/` 的镜像映射在 HMCL core 里**不存在**，必须自己按 BMCLAPI 文档实现。
接口形状参考 `download/DownloadProvider.java`（我们自己的 `DownloadSource` 照它设计，但不要直接依赖它）。
用 `curl -I` 逐个探测并报告状态码与耗时：
```
https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json
https://bmclapi2.bangbang93.com/versions/<某个版本>/<某个版本>.json
https://bmclapi2.bangbang93.com/maven/<某个 library 的 maven 路径>
https://bmclapi2.bangbang93.com/assets/<sha1 前两位>/<sha1>
https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
https://libraries.minecraft.net/
https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml
https://meta.fabricmc.net/v2/versions/loader/1.20.1
https://meta.fabricmc.net/v2/versions/loader/1.20.1/<某个 loader 版本>/profile/json
https://maven.minecraftforge.net/
https://maven.neoforged.net/releases/
```
同时读 `vendor/hmcl/` 里与下载/安装相关的入口（给出类名 + 方法签名）：
- `download/`（`DownloadProvider` / `RemoteVersion` / 文件下载任务）
- `game/` 里解析版本 json 的入口（注意别重复实现）
报告里必须回答：**哪些能直接复用 HMCLCore，哪些必须自己写**。不确定的列出来，不要猜。

## 第 1 步：版本清单（meta）
- 缓存到 `<appRoot>/data/meta/version_manifest_v2.json`，附 `fetchedAt`；TTL 6 小时
- 语义：先读缓存（离线可用）→ 过期则尝试刷新 → 刷新失败但缓存存在时**继续用缓存并显示"离线模式"**
- 解析字段：`id` / `type`（release|snapshot|old_*）/ `url` / `sha1` / `releaseTime`
- 单独缓存被选中的版本 json：`data/meta/versions/<id>.json`（带 sha1 校验）
- 单测：manifest 解析、TTL 判定、离线回退（固定 JSON 夹具，不联网）

## 第 2 步：下载源抽象（BMCLAPI 默认，可关闭）
```kotlin
interface DownloadSource {
    val id: String                         // "bmclapi" | "official"
    fun versionManifest(): String
    fun versionJson(id: String, url: String): String
    fun library(path: String): String      // 相对 maven 路径
    fun asset(hash: String): String
    fun clientJar(versionId: String, url: String): String
    fun loaderMaven(group: String, artifact: String, version: String, classifier: String?, ext: String): String
}
```
- **默认 `bmclapi`**；设置 →「下载」分组里给一个开关「优先使用国内镜像（BMCLAPI）」，
  关掉即走 `official`，两个源都要能跑通
- 若实现到 BMCLAPI：校验前先看响应头 `x-bmclapi-hash`（sha1 合法时可直接用它判断本地已有文件，
  照 `FetchTask.java:339-341` 的做法），能省掉一次重复下载
- 回退链：首选源失败（5xx / 超时 / 连接重置）→ 自动换另一个源重试一次 → 仍失败给出可见错误；
  日志记录每个文件实际使用的源
- **每个文件返回"候选 URL 列表"而不是单个 URL**（照 `download/DownloadCandidate(s)` 的语义）：
  依次尝试，404/超时/5xx 就切下一条。理由：BMCLAPI 的 `/maven` 是 **302 重定向到第三方 CDN**
  （实测首跳 20s 超时、换节点后 1.6s），302 要当正常路径跟随，不能当失败
- **超时放宽**：连接 20s / 读 60s / 单文件总超时 120s（实测 BMCLAPI 首跳会出现 20s 级延迟）
- 测试端点时**必须用真实存在于版本 json 里的 artifact 路径**：`libraries.minecraft.net` 只镜像
  MC 实际引用的构件，随便挑一个坐标报 404 不代表源坏了（例：1.20.1 引用的是 `asm:9.3`，拿 `9.5` 去测必 404）
- BMCLAPI 的路径映射与官方不同（`/mc/game/...`、`/maven/...`、`/assets/...`），**以侦察结果为准**，
  不要照抄记忆；maven 映射要能拼出 Fabric / Forge / NeoForge 的坐标
- 所有网络请求只能经过这一层：`grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource` 为空

## 第 3 步：原版下载与安装（空目录 → 可启动）
沿用 vanilla/HMCL 布局，**不要另造目录结构**：
```
<gameDir>/versions/<id>/<id>.json + <id>.jar
<gameDir>/libraries/...
<gameDir>/assets/indexes/<assetIndex>.json + assets/objects/<xx>/<hash>
```
- 下载器要求：
  - sha1 校验（来自 manifest / 版本 json），失败重下一次，再失败报错并清理
  - 断点续传：`.part` 文件 + HTTP `Range`；续传前先校验已有字节
  - 重试：3 次指数退避（1s/3s/9s），仅对 5xx / 超时 / 连接重置
  - 并发上限 8，进度用 `StateFlow<DownloadProgress>`（总数/完成/字节/当前文件）
  - 取消必须真取消：协程 cancel + 关闭连接 + 清理 `.part`
  - `User-Agent: YMCL/0.1 (+local)`，超时：连接 15s / 读 30s
- **覆盖保护**：`versions/<id>/` 已存在 → 直接拒绝并提示"实例已存在"，不允许静默覆盖
- UI：从"获取内容 → 下载游戏版本"进入的对话框（列表按 release/snapshot 分组、可搜索、显示类型与发布时间），
  下载中显示进度与取消按钮；完成后回到实例页并选中新实例

## 第 4 步：加载器安装
优先复用 HMCLCore 的安装器；不可用时再自己实现，并在报告里说明走的是哪条。
- **Fabric**：**loader 版本枚举用 `maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml`**
  （实测 8.5s 可用；`meta.fabricmc.net/v2/versions/loader/<mc>` 实测每次跑满超时，只当兜底），
  选定版本后取 `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`（实测 10s）
  拿成品 profile，**落盘缓存到 `data/meta/fabric/<mc>-<loader>.json`（TTL 24h）**；
  它已写好 inheritsFrom / mainClass / libraries（asm、mixin、intermediary），
  **不要自己拼 profile**，`maven.fabricmc.net` 只用于下载 profile 里引用的 jar
- **Forge**：`maven.minecraftforge.net` 的 installer jar → 用 JDK 执行
  `java -jar forge-installer.jar --installClient <gameDir>`（先确认 installer 支持的参数，别照抄记忆）
- **NeoForge**：同 Forge 思路，域名 `maven.neoforged.net`。**实测该域 3 次全 000（20s/15s 超时）**，
  所以 P3 **不验收 NeoForge**：只留接口与"暂不可用"的可读错误，不写安装实现。
  若你要排查可达性，先试 `curl -4`、`--resolve`、以及换 DNS，结论写进报告，不要在本阶段强推
- **Quilt**：与 Fabric 同构，P3 允许只做接口不实现，报告里写明
- 每种 loader 装完必须能在实例列表里看到，并且**能启动进入主菜单**

## 第 5 步：测试与验收
**测试一律用隔离目录** `~/ymcl-test/mc`（可删可重建）。允许从 `~/Minecraft` **复制**版本作为素材，
**禁止写入 `~/Minecraft`**。

验收（每条都要真实输出，缺一不算过）：
1. `./gradlew build` / `test` 通过；新增单测覆盖：manifest 解析、TTL/离线回退、sha1 失败重试、
   Range 续传、源路径映射
2. **空目录装 1.20.1 原版** → 启动进入主菜单（贴日志前 10 行 + `grim` 截图），日志标注所用源
3. **关掉 BMCLAPI 开关**另起目录再装一次 → 走 official 成功；把两次的源标记并排贴出来
4. 装 1.21.1 + Fabric → 启动进入主菜单（贴 Fabric loader 版本与日志关键行）
5. 装一个 Forge 实例（1.20.1 优先）→ 启动进入主菜单，日志里能看到 `forgeclient`
6. 对已存在的版本再点一次安装 → 被拒绝，原目录 `sha1sum` 不变（贴对比）
7. 断源（源指向不可达地址）→ manifest 走缓存仍能列出版本，UI 标注"离线模式"
8. 下载中途取消 → 无残留进程、`.part` 被清理、无半截 install
9. `grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource` 为空
10. `~/Minecraft` 未被修改：开工前 `touch ~/ymcl-test/.stamp`，收尾 `find ~/Minecraft -newer ~/ymcl-test/.stamp | head`
    为空（复制出去的副本不算）
11. 报告给出：改动文件:行、`git log --oneline hy4-start..HEAD`、`git tag --list`

## Git 纪律
- 分支 `p3-install`；每个里程碑一个能 build 的 commit：
  `feat(meta): version manifest cache` → `feat(download): source abstraction (bmclapi default)`
  → `feat(install): vanilla install` → `feat(install): fabric loader` → `feat(install): forge loader`
  → `feat(ui): download dialog + progress`
- tag：`p3-meta`（清单+下载器通）、`p3-vanilla-ok`（原版可启动）、`p3-loader-ok`（Fabric+Forge 可启动）；
  **一个 tag 一个 commit，不要两个 tag 打在同一点**
- 禁止 force push / 改写历史 / 移动已有 tag

## 不要做
- 不做 Mod 市场（P4）、整合包（P5）、诊断（P6）、Java 自动安装（P7）
- 不下载/分发任何 Minecraft 游戏资源到仓库（只下载到本机 gameDir）
- 不改 `vendor/hmcl/` 源码；不改 `design/`、`NOTES.md` 的规格
- 不引新 UI 库、不引 DI 框架、不加 HTTP 客户端之外的依赖（用 JDK HttpClient 即可）

## 卡住时
- 某个源不可达 → 换另一个源，并在报告里写清实测结果，不要伪造"已通过"
- 加载器安装参数不确定 → 跑 `java -jar installer.jar --help` 拿真实输出，不要凭记忆
- HMCLCore 里有现成能力 → 直接用，别重复实现；确实没有才自己写，并在报告里注明
