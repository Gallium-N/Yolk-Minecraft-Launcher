# P3 v2 能力矩阵（能力 → 证据 → 状态）

> 任务书：`prompts/p3-complete-no-debt.md`。分支 `p3-no-debt`，基线 `509f15a`。
> 状态：✅ 真机验证 / ⚠️ 已实现且有离线单测、真机未单独取证 / ❌ 未跑（写明原因）。
> 约定：**没有证据的能力一律标 ❌ 或 ⚠️，不把「代码写了」当「跑过」**。
> 真机截图均在 `build/p3-v2-shots/`，原始日志在 `/tmp/p3-*.log` 与 `~/.local/share/ymcl/data/logs/`。

## A. 加载器四件套

| 能力 | 证据 | 状态 |
|---|---|---|
| 统一入口 `LoaderInstaller.install(kind, mc, loaderVersion, instanceId)` | `app/LoaderInstaller.kt:57`；P3NoDebtTest 6 个离线单测 | ✅ |
| Fabric 1.20.1 装上启动（回归不退化） | `loaderV2SelfCheck -Dymcl.v2.mode=fabric`：`Loading Minecraft 1.20.1 with Fabric Loader 0.20.1` / `V2_OK mode=fabric`；`p3-fabric-menu.png` 主菜单 | ✅ |
| Forge 1.20.1 + 47.2.0 装上启动 | `mode=forge`：`V2_LOADER kind=forge version=47.2.0 libs=117`、`forge: 自包含 profile libraries=117`、`Launching target 'forgeclient' with arguments`、`V2_OK mode=forge`；`p3-forge-menu.png` | ✅ |
| Quilt 1.20.1 装上启动 | `mode=quilt`：`V2_LOADER kind=quilt version=0.24.0 libs=14`、`Loading Minecraft 1.20.1 with Quilt Loader 0.24.0`、`Quilt Loader ... 0.24.0 ... KnotClient`、`V2_OK mode=quilt`；`p3-quilt-menu.png` | ✅ |
| NeoForge 三次 `curl -sI --max-time 20` 探测 | `http=200` ×3（本机现可达，与任务书「三次 000」不同）；单测 `neoforge unreachable throws a readable error with attempt count` 覆盖不可达分支 | ✅ |
| NeoForge 可达时真机装 1.20.4 并启动 | `mode=neoforge -Dymcl.v2.mc=1.20.4 -Dymcl.v2.loaderVersion=20.4.253`：`Successfully installed client`、`自包含 profile libraries=137`、`NeoForge mod loading, version 20.4.253, for MC 1.20.4`、`V2_OK mode=neoforge`；`p3-neoforge-menu.png` | ✅ |
| Forge `launcher_profiles.json` 补写 + 117 库自包含 profile | 单测 `forge writes launcher_profiles and a self contained 117 library profile` 断言 `libraries=117`、无 `inheritsFrom`、`jar=Forge-Test`、含 `forgeclient` | ✅ |
| 四种 kind 进 LoaderKind / 实例加载器列 / 整合包 dependencies 映射 | `domain/Loader.kt:4`、`InstanceSummary.loaderLine`、`ModpackParser.loaderFromDependencies`、`ModpackBackendImpl.installBase` 统一调用 | ✅ |

## B. 老版本 virtual / map_to_resources assets

| 能力 | 证据 | 状态 |
|---|---|---|
| 1.6–1.12 判定 + `virtual`/`map_to_resources` 双触发 | `app/LegacyAssets.kt`；单测 `legacy version range is 1 6 through 1 12 only`、`virtual plan copies...`、`modern version without explicit virtual flag has no plan` | ✅ |
| 复制成 `assets/virtual/<mc>/<原名>` + `resources/` | `legacySelfCheck`：`virtual assets: root=assets/virtual/1.12.2 objects=1305 copied=1305 map_to_resources=true dropped=0 missing=0` | ✅ |
| 原始名含空格 / 中文、拒绝 `..`/绝对/盘符 | 单测 `asset names keep spaces and CJK but reject traversal absolute and drive` | ✅ |
| `instance.json` 记 `assetsVirtual=true`、重装幂等 | `LEGACY instance.json assetsVirtual=true`；二次安装 `copied=0`（幂等） | ✅ |
| 启动 `--assetsDir` 指到 virtual 树 | `HmclEngine.readInstanceFlag` + `HmclGameInstance.getActualAssetDirectory`；`p3-legacy-menu.png` 有全景材质、`Minecraft 1.12.2` 左下角版本 | ✅ |
| 1.12.2 真机装 → 主菜单、声音/材质正常 | `LEGACY_MARKER Setting user: YMCL` / `LWJGL Version: 2.9.4`、`LEGACY_OK mc=1.12.2 java=8 virtual=1305`；`p3-legacy-menu.png` | ✅ |
| 1.7.10（可选） | 未跑（任务书标注可选） | ❌ |

## C. 老版本 Java 矩阵

| 能力 | 证据 | 状态 |
|---|---|---|
| 边界 ≤1.16→8 / 1.17–1.20.4→17 / 1.20.5+→21 / 未知→21 | `app/JavaMatrix.kt`；单测 `java matrix boundaries 1 16 to 1 20 5` | ✅ |
| 按矩阵挑已注册 Java（就近，不选过高的 25）；旧版精确 8；找不到 → 可读错误 | 单测 `java matrix selects exact 8 for old mc and rejects when missing`；`AppState.launch` 手动选 Java 优先不被覆盖 | ✅ |
| 启动日志打印选中 Java 路径与主版本 | Forge/Fabric/Quilt/NeoForge：`YMCL java selected path=/usr/lib/jvm/java-21-temurin-jdk/bin/java major=21 required=17 mc=1.20.1`；legacy：`LEGACY java path=.../jdk8u512-b09-jre/bin/java major=8` | ✅ |
| 1.12.2 用 Java 8 / 1.20.1 用 Java 21 各跑一次 | 上两行（JRE8 为 `build/jdks/` 下仅测试用解压包，已 gitignore） | ✅ |

## D. 传输层补齐

| 能力 | 证据 | 状态 |
|---|---|---|
| `.part` 带 `<size>` 与 `<sha1 前缀>`，跨重启 Range 续传，前缀不符丢弃重下 | `GameDownloader.writePartMeta`；单测 `part with mismatched sha1 prefix is discarded and re downloaded` | ✅ |
| 「装到一半 kill，重启从已有字节继续」真机 | `resumeSelfCheck` 两个**独立 JVM**：phase=kill 落盘 5,000,000 字节后 `halt(137)`；phase=resume 日志 `client.jar resume from 5000000 bytes` → `RESUME_OK resumed=true finalSha1=0c3ec587... size=23028853` | ✅ |
| `x-bmclapi-hash` 第二次安装命中「本地已有 → 跳过」 | `legacySelfCheck` 二次安装 `install summary: files=1349 ... skipped=1345`；日志 `... <- skip(local, bmclapi-hash=...) 0ms ...B`；单测 `second install hits skip local bmclapi hash` | ✅ |
| official-only 关镜像装一次、零 bmclapi 请求 | `coldPathSelfCheck -Dymcl.cold.official=true`：`COLD source=official`、`COLD_INSTALL ... failures=0`、`COLD_RESULT ok=true`；90 条真实下载全部 `<- official`，`grep -c bmclapi`（非 skip 行）= **0** | ✅ |
| P3 传输参数（30s/300s/熔断阈值）未改 | `HttpDownloadTransport`、`HostHealth` 本轮零改动 | ✅ |

## E. 界面与数据三个错

| 能力 | 证据 | 状态 |
|---|---|---|
| 基座 `role: base`，纯版本号 + 空 mods + 无 displayName 也过滤；过滤不删目录 | `GameInstaller.writeInstanceMetadata`、`FileInstanceRepository.isBase`；单测 `base role instances are filtered but dirs are kept` | ✅ |
| 实例列表无基座行 | `p3-instances-fo49.png`：`共 1 个实例`，只有 `Fabulously Optimized` | ✅ |
| `modCount` 数 `mods/*.jar`（忽略 .disabled/子目录），非隔离数根 mods | `FileInstanceRepository.modCount`；单测 `modCount counts only jar files and ignores disabled` | ✅ |
| FO 显示 `49 个模组` | `p3-instances-fo49.png` 行内 `49 个模组`；`command ls -1 mods \| wc -l` = 49 | ✅ |
| 启动宏 `${clientid}`→32hex、`${auth_xuid}`→空串，日志 `grep -c '${'` 为 0 | `LaunchMacros` + `YmclLauncher.getConfigurations()`；Forge 日志 `--clientId 0ae3... --xuid ""`，游戏日志 `grep -c '\${'` = **0**；单测 `launch macros are 32 hex, empty xuid and no unresolved placeholders` | ✅ |

## F. 整合包导入并行化

| 能力 | 证据 | 状态 |
|---|---|---|
| `files[]` 并发 8，复用 P3 并发语义，不动 HttpDownloadTransport 参数 | `ModpackBackendImpl` `Executors.newFixedThreadPool(8)` + 单个 GameDownloader；单测 `eight downloads proceed concurrently through one downloader` | ✅ |
| FO（52 文件 / 27MB）≤ 2 分钟 + 日志时间戳并发 | 真机 `p3-parallel` 全新 gameRoot：`IMPORT file 1/52 ... at 1794320770229` → `IMPORT file 52/52 ... at 1794320775676`（52 文件约 5.4s），`IMPORT summary: ... files=52 bytes=27007030 ... failures=0`；进程启动到完成 < 76s | ✅ |

## G. 新建实例 / 安装游戏版本界面入口

| 能力 | 证据 | 状态 |
|---|---|---|
| 实例页「新建实例」按钮 + 空状态「获取内容」指向向导 | `InstancesPage.kt`；`p3-instances-fo49.png` 右上「新建实例」 | ✅ |
| 版本列表来自 `MetaRepository.loadManifest()`（922 条）、类型分组/搜索/徽章/日期、失败可读错误+重试 | `p3-wizard-versions.png`：`安装游戏版本 · 922 个版本`、release 徽章、日期 | ✅ |
| 加载器段选 + 异步拉版本列表（默认最新稳定）+ NeoForge 不可达置灰原因 | `p3-wizard-loader.png`：Fabric 选中、`默认选最新稳定：0.20.1`；`p3-neoforge-greyed.png`：NeoForge 置灰 + 红字 `NeoForge 官方源不可达（已探测），请检查网络或代理；该选项已置灰。` | ✅ |
| 实例名默认 `<mc> · <加载器>`、instanceId 实时显示且冲突递增、目标目录只读 | `p3-wizard-name.png`：`1.20.1 · Fabric` / `1-20-1-Fabric` / `versions/1-20-1-Fabric` | ✅ |
| 阶段化进度 7 阶段 + 可取消 | `p3-wizard-installing.png`：`正在安装` / stage `assets` / `阶段进度 4/7` / `已下载 0 B` / `当前文件 —` / `取消安装` + `已下文件保留，下次安装复用` | ✅ |
| 完成跳实例页并选中、状态就绪 | `p3-wizard-resumed.png`（安装完成后的实例页）：`1 个实例`、`1.20.1 · Fabric 0.20.1`、`就绪` | ✅ |
| 失败/取消 → 停当前页、`installState: broken`、可「继续安装」 | `p3-wizard-cancelled.png`：`安装未完成` / `安装已取消（未完成）...` / `继续安装`·`重新选择`；磁盘 `installState:"broken"` | ✅ |
| 取消后「继续安装」复用已下文件并装完 | 真机 `resume-flow`：取消 → `installState:"broken"` → 点「继续安装」→ `install summary: files=3689 ... failures=0 skipped=3688`（已下文件复用，`skip(local, bmclapi-hash=...)`）→ `NEW_INSTANCE loader=0.20.1 libs=8` → `broken` 标记清除 → 实例页 `就绪`；`p3-wizard-resumed.png` | ✅ |
| 取消清 `.part` 但保留已下文件、明确提示复用 | `InstallProgressCard` 文案 + `NewInstanceBackendImpl.cancel()`；`cancelAtStage=4` 真机日志 `downloaded files kept` | ✅ |
| 长任务后台、离开页面不中断 | `CoroutineScope(SupervisorJob()+Dispatchers.IO)` 持于 UiState（不随 Composable 销毁） | ⚠️（代码具备，未做离开页面再回来的真机专项） |
| 原版入口选「原版」装另一个实例并启动 | 未跑（本轮向导选的是 Fabric） | ❌ |
| NeoForge 置灰截图 | `p3-neoforge-greyed.png`（`-Dymcl.qa.neoforge.unreachable=1` 复现不可达分支） | ✅ |
| **删除未完成的安装（新增）** | `p3-broken-cleanup-card.png`（版本页 `未完成的安装 · 2 个` 卡片，base 不出现）、`p3-broken-cleanup-confirm.png`（确认框含实例名+完整路径）、`p3-broken-cleanup-after.png`（删 1 个后变 `1 个`）；`p3-error-with-delete-btn.png`（错误页「删除未完成的安装」按钮）、`p3-delete-confirm.png`；真机 `DELETE_BROKEN id=... removed=true`、目录消失、其它 broken 与 base 均保留；`DeleteBrokenTest` 5 个离线单测（只删 broken / 拒正常、基座、路径穿越、绝对/盘符） | ✅ |

## 构建 / 约束

| 能力 | 证据 | 状态 |
|---|---|---|
| `./gradlew build` 绿、新增单测 ≥12 | `BUILD SUCCESSFUL`，全量 124 个测试 PASSED（P3NoDebtTest 18 + DeleteBrokenTest 5 为本轮新增） | ✅ |
| 三项 grep 空（org.jackhuang / https:// 外 / java.io\|nio\|awt commonMain） | `https://` 只在 `app/DownloadSource.kt`；commonMain 无 `org.jackhuang` / `java.io|nio|awt` | ✅ |
| A–G 各段 commit、每个独立 build 通过 | `git log 509f15a..HEAD` | ✅ |
| `docs/HANDOFF.md` §4 只剩 P6/P7/P8、已知坑补 virtual/自包含/基座 role、§8 补 `p3-v2` | `docs/HANDOFF.md` | ✅ |

## 真机运行记录（原样输出）

- 四加载器：`V2_OK mode=fabric` / `V2_OK mode=forge` / `V2_OK mode=quilt` / `V2_OK mode=neoforge`
- 老版本：`LEGACY virtual assets/virtual/1.12.2 files=1305 resources/ files=1305`、`Setting user: YMCL`、`LWJGL Version: 2.9.4`、`LEGACY_OK mc=1.12.2 java=8 virtual=1305`
- 续传：`client.jar resume from 5000000 bytes` → `RESUME_OK resumed=true finalSha1=0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838`
- official-only：`COLD source=official`、`COLD_RESULT ok=true`、90 条 `official`、0 条 bmclapi 请求
- bmclapi-hash 跳过：`install summary: files=1349 ... skipped=1345` + `skip(local, bmclapi-hash=...)`
- 删除未完成：`DELETE_BROKEN id=1-20-1-Fabric removed=true` / `NEW_INSTANCE deleted broken install id=...`
- 并行导入：`IMPORT file 1/52 ... at 1794320770229` → `IMPORT file 52/52 ... at 1794320775676` → `IMPORT summary: ... failures=0`
