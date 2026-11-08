# P4 第 2 步：Modrinth 资源下载（搜索 → 选版本 → 装进实例）

> 任务书只读。commit 切分：`feat(modrinth)` API+安装、`feat(ui)` 获取内容页、`test(modrinth)`。
> 每个 commit 独立 `./gradlew build` 通过。

## 范围

只做 Modrinth，**不做 CurseForge**（下一步）、**不做整合包导入**（P5）、不做资源包/光影界面（先只做 mod）。

## API（全部实测口径，别凭记忆）

```
GET https://api.modrinth.com/v2/search?query=<q>&limit=20&offset=0&index=relevance&facets=<json>
GET https://api.modrinth.com/v2/project/<idOrSlug>
GET https://api.modrinth.com/v2/project/<idOrSlug>/version?game_versions=["1.20.1"]&loaders=["fabric"]
GET https://api.modrinth.com/v2/version/<versionId>
GET https://api.modrinth.com/v2/tag/loader | /v2/tag/game_version | /v2/tag/category
文件 CDN: version.files[].url（cdn.modrinth.com，直连，无需 key）
```

- **必须带 `User-Agent: YMCL/0.1 (local)`**，Modrinth 会拒没有 UA 的请求
- 限流 300 req/min；收到 429 读 `Retry-After` 退避，**不要**立刻重试
- `facets` 是二维 JSON 数组字符串，例：
  `[["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]`
- `version.files[]` 里有 `hashes.sha1` / `sha512`、`filename`、`primary`
- `version.dependencies[]`：`dependency_type` ∈ `required|optional|incompatible|embedded`，
  带 `version_id` 或 `project_id`

## 结构

- **所有 URL 仍归 `DownloadSource`**（AGENTS 硬规则）：新增
  `modrinthSearch() / modrinthProject(id) / modrinthVersions(id, mc, loader) / modrinthVersion(id) / modrinthFile(url)`，
  候选列表长度 1（Modrinth 没有国内镜像，别假装有）。`grep https:// ... | grep -v DownloadSource` 必须仍为空
- `app/ModrinthClient.kt`：只用 `MetaFetcher`（取 JSON 字符串）与 `DownloadTransport`（取文件），不新建 HTTP 栈
- `app/ModResolver.kt`：纯 Kotlin，无 IO，输入「候选版本 + 已装 mod 的 projectId 集合 → 待装列表」
- `app/ModInstaller.kt`：落盘 + 校验 + 冲突检测

## 关键行为（这些才是难点，UI 不是）

1. **只列兼容版本**：`game_versions` + `loaders` 双过滤；实例是 Forge 就查 `loaders=["forge"]`，
   是 Fabric 就 `["fabric","quilt"]`？——**不**，quilt 只在你确定兼容时才列，默认严格按实例 loader
2. **默认选版本**：优先 `version_type=release` 的最新一条；没有 release 才退 `beta`；`alpha` 默认不选但可见
3. **依赖补齐**：只自动装 `required`；`optional` 列出来让用户勾（默认不勾）；
   `incompatible` 若已装则**阻止安装并说明**；`embedded` 视为已包含
   - 递归解析 `version_id`，**深度上限 5**，用「projectId@versionId」集合防环
   - 已在 `mods/` 里（按 sha1 或 projectId 记录）就跳过
4. **文件名安全**：用服务端 `filename`，但必须剥掉路径分隔符、拒绝 `..`、长度截断到 100 字符
5. **运行中禁止安装**：实例进程活着时拒绝写入并给出提示（Windows 会锁文件）
6. **校验**：装完核对 `sha1`；不匹配 → 删掉重下一次 → 仍失败进失败清单并报可读错误
7. **不认识的字段忽略**，反序列化不许因为有新字段就整页崩

## UI（获取内容页）

- 顶部：实例选择器（默认主实例），显示 `<实例名> · <mc> · <loader>`；换实例刷新结果
- 搜索框 + 「模组」标签；结果行：图标、标题、作者、下载量、版本号、渠道徽章（release/beta）
- 「安装」按钮 → 显示依赖清单（required 锁定、optional 可勾）→ 确认后装，进度条 + 当前文件名
- 装完 toast + 列表状态变「已安装」；无兼容版本时明确写「1.20.1 + Fabric 下没有发布版本」
- 空搜索词 → 显示热门（用 `index=downloads` 不带 query）
- 加载中/失败都要有明确状态，禁止空白页

## 单测（≥7，离线）

1. facets 构造：mc + loader + project_type 三者都在
2. 版本筛选：release 优先于 beta；alpha 不进默认选择
3. 依赖递归：A→B(required)→C(required) 三层解析正确
4. 依赖成环：A→B→A 不死循环，抛出可读错误
5. 深度超限（>5）被截断并记录
6. 文件名 `../../etc/passwd` 被拒绝
7. sha1 不匹配 → 触发一次重下，仍失败进失败清单
8. 实例运行中 → 拒绝安装

## 验收（缺一不算过）

1. `build`/`test` 绿；新增单测 ≥7；三项 grep 仍为空（含 Modrinth 之后）
2. **真机**：选 `Fabric-Lab`，搜 `sodium` → 装最新 release → `versions/Fabric-Lab/mods/` 出现对应 jar，
   sha1 与 API 一致（贴命令与两个 hash）→ 启动进主菜单
3. **依赖补齐真机**：新实例（Fabric，1.20.1，mods 空）只装 `iris` → 自动把 `sodium` 一起装进去，
   贴 install 日志（`resolved dependency sodium-<ver> (required)`）
4. **不兼容拦截**：同一 Fabric 实例尝试装 Forge-only 的 mod（如 `jei` 的 forge 文件）→ 查不到版本或被拒，
   给可读提示，不许静默失败
5. **UA 与限流**：日志里能看到请求带 `User-Agent: YMCL/0.1`；429 分支有单测覆盖
6. 运行中拒绝：实例启动着点安装 → 明确提示（贴截图或日志）
7. 截图：获取内容页搜索结果 + 依赖确认弹窗，**窗口级 grim + PID 断言**（沿用上轮做法，不许改 WM_CLASS）
8. 报告：`文件:行`、`git log --oneline 81d3250..HEAD`、`git status --porcelain`、jar 的 sha1 双比对

## 不要做

CurseForge、mrpack 导入、资源包/光影、新建实例向导、删除实例、
P3 下载器与熔断逻辑的任何改动、对 `~/Minecraft` 的写入。
