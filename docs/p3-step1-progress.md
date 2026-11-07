# P3 第 1 步完成记录（meta 层）

> 2026-11-07 完成。本文件替换 2026-11-06 的暂停存档。
> 范围：manifest 缓存（TTL 6h + 离线回退）、版本 json 落盘（sha1 校验）、Fabric profile 获取与缓存（24h）、
> DownloadSource 候选列表纯函数。**未做**：下载器 / 安装 / UI（第 3 步）。
> 基线：`p3-start` = `fc3e92e`；分支 `p3-install`。

## 验收结果（6 条）

| # | 结果 | 证据 |
|---|---|---|
| 1 | `./gradlew build` / `test` 通过；**31 tests / 0 failures**（23 原有 + 8 新增） | `DownloadSourceTest` 3、`MetaRepositoryTest` 5 |
| 2 | 联网自检：manifest **922** 条；1.20.1 sha1 `72ae93b0934df5541860e3e6b72cf883c74da491`；Fabric mainClass `net.fabricmc.loader.impl.launch.knot.KnotClient`、**8** libraries、前两条 `org.ow2.asm:asm:9.10.1` / `org.ow2.asm:asm-analysis:9.10.1` | `./gradlew metaSelfCheck` |
| 3 | 缓存落盘：manifest 322145 B、`versions/1.20.1.json` 34974 B、`fabric/1.20.1-0.20.1.json` 2862 B | `ls -l <appRoot>/data/meta/...` |
| 4 | 缓存 mtime 改到 6h 前 + 源指向不可达地址 → `offline=true`、打印「离线模式：使用本地缓存」、仍列出 922 条 | `./gradlew metaSelfCheck -Dymcl.meta.mode=offline` |
| 5 | 更正 1：official `asm 9.3` → 200 1.226483；`asm 9.5` → 404 1.022014 | curl |
| 6 | 文件:行、`git log --oneline p3-start..HEAD`、`git status --porcelain` | 见第 1 步聊天报告 |

## 实现文件（文件:行）

| 文件 | 关键行 |
|---|---|
| `composeApp/src/commonMain/.../domain/MetaModels.kt` | `:7` `VersionEntry`、`:16` `VersionManifest`、`:22` `CachedManifest`、`:30` `FabricProfile`、`:38` `FabricLibrary` |
| `composeApp/src/commonMain/.../app/DownloadSource.kt` | `:6-14` URL 常量、`:23` 接口、`:44` `BmclapiDownloadSource`、`:93` `OfficialDownloadSource`、`:148` `UnreachableDownloadSource`、`:161` `defaultDownloadSource` |
| `composeApp/src/commonMain/.../app/MetaCachePolicy.kt` | `:14` object、`:15` `MANIFEST_TTL_MS=6h`、`:16` `FABRIC_PROFILE_TTL_MS=24h`、`:19` `isExpired` |
| `composeApp/src/commonMain/.../app/MetaRepository.kt` | `:14` `MetaStore`、`:22` `MetaFetcher`、`:30` class、`:51` `loadManifest`、`:91` `loadVersionJson`、`:119` `loadFabricLoaderVersions`、`:133` `loadFabricProfile` |
| `composeApp/src/desktopMain/.../platform/MetaPlatform.desktop.kt` | `:15` `FileMetaStore`、`:39` `HttpMetaFetcher`（`:47` 起候选重试） |
| `composeApp/src/desktopMain/.../MetaSelfCheck.kt` | `:11` `main()` |
| `composeApp/src/desktopTest/.../DownloadSourceTest.kt` | 3 个单测 |
| `composeApp/src/desktopTest/.../MetaRepositoryTest.kt` | 5 个单测 |
| `composeApp/build.gradle.kts` | `:91` `metaSelfCheck` JavaExec task |

## 设计要点

- 所有 `https://` 字面量只在 `app/DownloadSource.kt`；约束 `grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource` 为空。
- 每个方法返回 `List<String>` 候选，依次尝试；404/5xx/超时切下一条；302 用 `HttpClient.Redirect.NORMAL` 跟随，不当失败。`HttpMetaFetcher` 对超时/5xx 重试 1 次（404 不重试）。
- 超时：连接 20s / 读 60s / 单文件总 120s；UA `YMCL/0.1 (+local)`。
- Fabric 枚举：优先 `maven.fabricmc.net/.../maven-metadata.xml`（实测 255 个版本，取最后 `0.20.1`）；`meta.fabricmc.net/v2/versions/loader/<mc>` 只当兜底。profile 取成品 json，缓存 `data/meta/fabric/<mc>-<loader>.json` TTL 24h，**不自拼 profile**。
- TTL 用**文件 mtime**（验收 4 用 `touch -d '6 hours ago'` 触发），JSON 内 `fetchedAt` 作冗余展示。
- manifest 缓存是带 `schemaVersion` 的包装 JSON；`versions/<id>.json` 与 `fabric/*.json` 是外部原始数据的逐字节副本（保持 sha1、可当版本 json 用）。
- commonMain 无 java.io；`MetaStore` / `MetaFetcher` 接口在 commonMain，实现只在 desktopMain。

## 已知限制

- `meta.fabricmc.net` 连接偶发逼近 20s 连接上限（同 client 顺序 4 请求实测第 4 个 19.2s，一次自检触发 `HttpConnectTimeoutException`）；已由 fetcher 重试覆盖，重试后自检通过。
- NeoForge 本阶段不验收（域三次 000 超时），本步未涉及。
- 验收 4 把 manifest 缓存 mtime `touch` 到 6h 前；下次联网自检会自动刷新。
- 本机 `JAVA_HOME` 默认指向 JDK 25，所有 gradle 命令须显式 `JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk`。
