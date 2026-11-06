# YMCL · P4 提示词：资源获取（Modrinth / CurseForge）

> 整段复制给编码代理。前置：P3 完成（原版与加载器安装可跑通），`p3-loader-ok` 已打。
> 本阶段只做「浏览 + 下载 + 安装到实例」；整合包**导入**属于 P5，不要顺手做。

---

# 任务：P4 — Modrinth / CurseForge 资源获取与安装

## 背景与已确认事实
- 项目 `~/src/ymcl`：五层结构 + vendored HMCLCore（**禁止改 vendor 源码**）；UI 禁 `org.jackhuang.*`；
  domain 禁 `java.io`
- P3 之后已有：版本清单缓存、`DownloadSource`（默认 BMCLAPI，可关）、原版与 Fabric/Forge 安装
- 测试目录约定：`~/ymcl-test/mc`；`~/Minecraft` 只允许**复制**出去，禁止写入
- HMCL 上游已有 Modrinth / CurseForge 实现（在 `vendor/hmcl/` 里），**优先复用它的解析与请求层**，
  自己重写要在报告里说明原因
- 用户明确要求：能从 Modrinth / CurseForge 下载 **Mod、整合包等资源**；整合包本阶段只负责
  "下载到本地并展示"，导入在 P5

## 第 0 步：侦察（只读，先出报告再写代码）
1. API 探测（`curl -I` 或取一条真实响应，报告状态码与限流头）：
```
https://api.modrinth.com/v2/search?query=sodium&limit=1
https://api.modrinth.com/v2/project/sodium
https://api.modrinth.com/v2/project/sodium/version?game_versions=%5B%221.20.1%22%5D&loaders=%5B%22fabric%22%5D
https://cdn.modrinth.com/                                       (文件 CDN)
https://api.curseforge.com/v1/mods/search?gameId=432&searchFilter=jei   (需 x-api-key)
https://edge.forgecdn.net/                                       (CF 文件 CDN)
```
2. 在 `vendor/hmcl/` 里定位可复用入口，给出类名 + 方法签名：
   `modrinth/`、`curseforge/`、`download/`，以及 addon/mod 相关的模型类；
   确认：搜索、项目详情、版本列表、文件下载 URL、依赖关系分别由谁提供
3. 报告必须回答：
   - 哪些能直接用 HMCLCore，哪些必须自己写
   - CurseForge 是否需要 API key；HMCL 的 key 在哪（**只用于判断，禁止复制到 YMCL**）
   - Modrinth 的 User-Agent 要求与速率限制实测结果

## 第 1 步：资源源抽象
```kotlin
interface ResourceSource {           // 两个实现：ModrinthSource / CurseForgeSource
    val id: String
    suspend fun search(q: String, kind: Kind, mcVersion: String?, loader: String?, offset: Int): List<ResourceHit>
    suspend fun project(id: String): ResourceDetail
    suspend fun versions(id: String, mcVersion: String?, loader: String?): List<ResourceVersion>
    suspend fun downloadUrl(file: ResourceFile): String   // 优先 CDN，其次原始 URL
}
```
- `Kind`：mod / modpack / resourcepack / shader
- 统一模型放 domain 层（无 HTTP 类型），API 类型只活在各自的 source 实现里
- 缓存：搜索结果 5 分钟内存缓存（同 query），项目详情 30 分钟；**不落盘大对象**
- 限流：识别 429 与 `Retry-After`，指数退避；`User-Agent: YMCL/0.1 (+local, contact=local)`
- **CurseForge key**：只从 `YMCL_CF_API_KEY` 环境变量或设置项读取；**禁止硬编码、禁止复用 HMCL 的 key**；
  无 key 时 UI 明确显示「CurseForge 需要 API Key（可在设置里填）」，Modrinth 不受影响

## 第 2 步：依赖解析
- Modrinth：version 的 `dependencies[]` 有 `dependency_type`（required / optional / incompatible）
- CurseForge：`relations`（`relationType` 对应 required/optional/incompatible）
- 规则：安装时**自动补齐 required**（递归，深度上限 5，去重）；optional 列出让用户自己勾；
  incompatible 命中已装文件时警告并阻止
- 依赖版本选择：优先同 mcVersion + 同 loader 的最新 release
- 单测：用两个固定 JSON 夹具做依赖闭包（含循环依赖、缺失依赖、深度超限三种情况），不联网

## 第 3 步：安装到实例
- 目标目录按 kind：`mods/`、`resourcepacks/`、`shaderpacks/`
- 文件名：用 API 提供的 filename，但必须过一遍 P1 的非法字符/长度清洗；重名时加 ` (2)` 而不是覆盖
- 冲突检测：按 projectId（写进 `data/addons.json`，带 `schemaVersion`）判断"已装/可更新"；
  同名不同 projectId 视为并存，给提示
- 下载：复用 P3 的下载器（`.part` + Range + sha1/`sha512` 校验 + 取消），不另写一套
- 安装/卸载后刷新实例详情，不改动版本 json
- **整合包文件**：只下载到 `<appRoot>/data/downloads/<project>-<version>.mrpack|zip` 并在 UI 显示路径与"待导入"，
  本阶段不解析不安装

## 第 4 步：UI
- 入口：实例详情/实例行 →「浏览资源」；以及「获取内容」面板里的「浏览模组与整合包」
- 对话框：顶部搜索框 + kind 切换（Mod / 整合包 / 资源包 / 光影）+ 版本与加载器过滤（默认取当前实例的值）
- 结果行：图标、名称、作者、下载量、最近更新、"已安装/可更新"标记、安装按钮
- 安装中：行内进度；依赖补齐时在结果下方列出"将一并安装：…"
- 无 key / 无网络 / 429 三种状态各有明确文案，不能空白页

## 第 5 步：测试与验收
用 `~/ymcl-test/mc` 里的测试实例（1.20.1-Fabric 与 1.20.1-Forge 各一）。

1. `./gradlew build` / `test` 通过；新增单测：搜索响应解析、版本过滤、依赖闭包（三种边界）、文件名清洗、
   `addons.json` 读写与 `schemaVersion`
2. **Modrinth 搜索 `sodium`** → 有结果（贴返回条数与首条名称/下载量）
3. 安装 `sodium` 到 1.20.1-Fabric 测试实例 → `mods/` 出现文件、sha512 校验通过，
   启动游戏日志里能看到该 mod 被加载（贴日志行）
4. **依赖自动补齐**：装 `iris`（依赖 `sodium`）→ `sodium` 被自动装上，UI 列出"将一并安装"
5. **版本过滤**：在 1.20.1 实例里搜同一项目，不兼容版本不出现（贴过滤前后的条数）
6. **CurseForge**：有 key 时搜索可用（贴首条结果）；无 key 时 UI 显示明确提示且不崩（贴截图）
7. 卸载一个 mod → 文件消失、`addons.json` 相应条目移除，游戏仍能启动
8. 下载中途取消 → 无 `.part` 残留、无半截文件
9. 整合包：从 Modrinth 下载一个 `.mrpack` 到 `data/downloads/`，UI 显示"待导入"，
   **确认没有解析或安装**（贴文件路径 + 目录里没有新实例）
10. `grep -rn "api.curseforge.com\|api.modrinth.com" composeApp/src --include=*.kt` 只出现在两个 source 实现里
11. `grep -rn "curseforge\|CF_API\|x-api-key" -i composeApp/src` 里**没有任何硬编码 key**（贴空结果）
12. `~/Minecraft` 未被修改（`find ~/Minecraft -newer ~/ymcl-test/.stamp | head` 为空）
13. 报告：改动文件:行、`git log --oneline p3-loader-ok..HEAD`、`git tag --list`

## Git 纪律
- 分支 `p4-resources`；里程碑 commit（每个都能 build）：
  `feat(resources): source abstraction` → `feat(resources): modrinth search/versions`
  → `feat(resources): curseforge (key-gated)` → `feat(resources): dependency resolution`
  → `feat(install): addon install/uninstall` → `feat(ui): browse dialog`
- tag：`p4-search`（两个源能搜）、`p4-install-ok`（装 mod + 依赖补齐 + 启动成功）
- 禁止 force push / 改写历史 / 移动已有 tag；一个 tag 一个 commit

## 不要做
- 不做整合包**导入**（P5）、不做诊断（P6）、不做 Java 安装（P7）
- 不复用/不外泄 HMCL 的 CurseForge key；不把任何 API key 写进仓库
- 不抓网页当 API（有官方 API 就用 API）
- 不引 DI 框架 / 新 UI 库；HTTP 用 JDK HttpClient
- 不改 `vendor/hmcl/` 源码；不动 `design/` 与 `NOTES.md` 规格

## 卡住时
- API 变更或返回结构不符 → 贴原始 JSON 片段（脱敏）并停下，不要猜字段
- HMCL 的复用边界不清 → 贴类名与签名，说明你打算怎么接
- 限流/403 → 记录响应头，换 key 或降频，不要伪造"搜索为空"
