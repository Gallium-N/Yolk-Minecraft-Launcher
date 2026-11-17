# 修：Forge / NeoForge 的 maven 走 BMCLAPI 候选（NeoForge 一直"不可达"的真正原因）

> 一个小 commit：`fix(download): bmclapi candidates for forge/neoforge maven`

## 实测事实（别再猜）

```
maven.neoforged.net  直连全部超时（DNS 被污染：2a03:2880:...:face:b00c / 174.37.154.236）
bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml
                     → 302 → 200，68,944 B，内容是真正的 <metadata>
bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.80/neoforge-21.1.80-installer.jar
                     → 302 → 200，6.8 MB，PK 头，有效 JAR
bmclapi .../maven/net/minecraftforge/forge/maven-metadata.xml  → 200，213,750 B
bmclapi .../maven/net/fabricmc/fabric-loader/maven-metadata.xml → 404（未镜像）
bmclapi .../maven/org/quiltmc/quilt-loader/maven-metadata.xml   → 404（未镜像）
```

结论：**BMCLAPI 镜像了 Forge 与 NeoForge 的 maven，没镜像 Fabric/Quilt。**
现在的 `neoforgeProbeUrl()` 只返回官方 URL，所以永远判"不可达"。

## 改法

1. `DownloadSource` 的这三个方法改成**候选列表**（镜像开启时 BMCLAPI 在前，官方兜底；
   关闭镜像时只有官方）：

```kotlin
fun neoforgeVersionMetadata(): List<String>   // [bmclapi/maven/..., official]
fun neoforgeInstaller(v: String): List<String>
fun forgeVersionMetadata(): List<String>      // 同样加 bmclapi 候选
fun forgeInstaller(mc: String, v: String): List<String>
```

2. **探测改成候选链**：依次尝试，**全部失败**才判不可达；日志每个候选一行：

```
neoforge probe: bmclapi/maven/... -> 200 214ms
neoforge probe: official -> timeout 15s
neoforge reachable via bmclapi
```

3. 探测结果缓存 5 分钟（`data/meta/probe/<loader>.json`），别每次进页面都打一遍。
4. 不可达时的文案要写出**试过哪些源**，不要只说"不可达"。
5. Fabric / Quilt **不改**（BMCLAPI 404，官方直连正常）。

## 验收（缺一不算过）

1. `build`/`test` 绿；新增 ≥2 单测（候选顺序随镜像开关变化、全失败才报不可达）
2. 镜像开启时：加载器步的 **NeoForge 选项不再置灰**，能列出 `21.1.x` 版本（贴探测日志）
3. **NeoForge 端到端真机**：装 1.21.1 + NeoForge 到实例 → 启动进主菜单，
   日志有 NeoForge 版本行（这条补上 P3 v2 里那个 ❌）
4. 关闭镜像：NeoForge 选项置灰 + 文案写明"官方源不可达"（截图）
5. Forge 走 BMCLAPI 时也要能装（回归：1.20.1 + Forge 47.2.0 仍能启动）
6. 窗口级截图 + PID 断言

## 不要做

改 Fabric/Quilt 的源、改下载器超时参数、动 P7/P8、加新 QA 开关。
