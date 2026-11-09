# P5：整合包导入（mrpack + CurseForge zip）

> 任务书只读。commit 切分：`feat(modpack)` 解析与安装、`feat(ui)` 导入向导、`test(modpack)`。
> 每个 commit 独立 `./gradlew build` 通过。

## 范围

只做**导入**：用户给一个整合包文件，我们把它变成能启动的实例。
**不做**整合包浏览/搜索（P4-step3 的 CurseForge 浏览需要 API key，单独再说）。

## 第 0 步：先修三个观感问题（5 分钟）

1. 获取内容结果行右侧显示原始版本串 `mc1.20.1-0.5.13-fabric` → 改成 `0.5.13` + 一个小 `fabric` 芯片
2. 搜索结果标题右边的 `『sodium』` 标签含义不明（看起来像调试残留）→ 要么改成「关键词：sodium」，要么删掉
3. 左侧导航栏文字压得太挤（88dp 里塞图标+中文标签）→ 保持图标 + 悬停 tooltip，只给选中项显示文字

## 两种格式

### mrpack（Modrinth）

zip 内：

```
modrinth.index.json
overrides/            ← 覆盖进实例根
client-overrides/     ← 只客户端，优先于 overrides
server-overrides/
```

`modrinth.index.json`：

```json
{
  "formatVersion": 1,
  "game": "minecraft",
  "versionId": "1.0.0",
  "name": "Fabulously Optimized",
  "dependencies": { "minecraft": "1.20.1", "fabric-loader": "0.15.11" },
  "files": [
    { "path": "mods/sodium.jar", "fileSize": 971552,
      "hashes": { "sha1": "...", "sha512": "..." },
      "env": { "client": "required", "server": "unsupported" },
      "downloads": ["https://cdn.modrinth.com/data/.../sodium.jar"] }
  ]
}
```

- `dependencies` 里的 `minecraft` 必装；`fabric-loader` / `quilt-loader` / `forge` / `neoforge` 决定加载器
  （`forge` 的值形如 `47.2.0`，`neoforge` 形如 `20.4.237`）
- `env.client == "unsupported"` 的文件**跳过**（那是纯服务端 mod）
- 本地自定义配置：`client-overrides/.launcher/instance.json` 若存在，合并进实例的 `instance.json`
  （这是我们自己的约定，别人的包里没有就忽略）

### CurseForge zip

zip 内：

```
manifest.json
overrides/
```

`manifest.json`：`minecraft.version`、`minecraft.modLoaders[]{id:"forge-47.2.0", primary}`、
`files[]{projectID, fileID, required}`、`overrides: "overrides"`。

**关键难点：manifest 里没有文件名**，下载地址是
`https://edge.forgecdn.net/files/<fileID/1000>/<fileID%1000>/<filename>`，必须拿到 filename。

三级策略，写进实现并在日志里标明用了哪级：

1. 用户配置了 CurseForge API key（`LauncherSettings.curseForgeApiKey`）→ 走官方 API 查 filename
2. 没 key → 走公共只读代理 `https://api.curse.tools/v1/cf/mods/<id>/files/<fileId>`
   （第三方，日志标注 `via=curse.tools`，设置里给开关，默认**开**，关掉则只走第 1/3 级）
3. 都不行 → 缺文件清单列出来，给可读错误：「N 个文件无法解析下载地址，需要 CurseForge API key 或允许代理」

**不允许**猜 filename 去拼 URL。

## 安装流程（两种格式共用）

1. 解压 zip 到临时目录（拒绝 zip-slip：`..` 与绝对路径一律丢弃，记一条警告）
2. 读 manifest，生成 `instanceId`（沿用 `InstanceId.slugify`，来源是包名；重名递增）
3. 装原版（`dependencies.minecraft` 或 `manifest.minecraft.version`）→ 装加载器（沿用 P3 的 Fabric/Forge 路径）
4. 逐个下载 `files[]` 到**实例目录**的 `path`（注意：`path` 是相对实例根，会落到 `mods/`、`config/`、
   `resourcepacks/` 等）；校验 sha1 用 index 里的值（CF 没有 hash，用 sha1 从 CDN 响应头或跳过校验并记 `unverified`）
5. 解压 overrides / client-overrides（后解压的覆盖先解压的，`client-overrides` 优先级最高）
6. 写 `instance.json`：`displayName`=包名、`loader`、`mcVersion`、`packSource`（`mrpack|curseforge`）、
   `packVersion`、`schemaVersion`；`machine.local.json` 不写（那是本机配置）
7. 失败清单沿用 P3 规矩：`IMPORT ERROR` + 文件名 + 原因，最后非零退出

**磁盘**：`assets/libraries` 走共享根，**不许**在实例目录里再复制一份。整合包里的 `files[]` 也不许写入共享根。

## 导入向导 UI

- 入口：实例页「导入整合包」按钮 + 把 zip **拖到窗口上**（Desktop 的 drop target）
- 解析后先显示确认页：包名、MC 版本、加载器与版本、文件数、总大小、覆盖文件数；
  解析失败 → 显示具体原因（不是"导入失败"四个字）
- 导入中：进度（阶段名 + 文件数 + 字节）、可取消（取消要停下载并清 `.part`，但**保留已解压的目录**并提示"未完成"）
- 完成：跳到实例页并选中新实例，状态「就绪」；重名实例不许静默覆盖
- 错误：缺文件/校验失败列成清单，界面上能滚动看完

## 单测（≥8，离线）

1. `modrinth.index.json` 解析：dependencies、files、env 都读对
2. `env.client == unsupported` 的文件被跳过
3. zip-slip：`../../evil.txt` 被丢弃并计数
4. `path` 是绝对值 → 丢弃
5. 包名 → instanceId，重名递增
6. `manifest.json` 解析：loader 版本、files 数量
7. CF filename 三级策略：有 key 走 API、无 key 走代理、都不可用抛可读错误（用假 fetcher）
8. overrides 优先级：`client-overrides` 覆盖 `overrides` 同名文件

## 验收（缺一不算过）

1. `build`/`test` 绿，新增单测 ≥8；三项 grep 仍为空
2. **真机 mrpack**：从 Modrinth 下 `Fabulously Optimized`（1.20.1 Fabric 那个版本）的 mrpack 文件，
   导入 → 实例出现 → 启动进主菜单，日志里能看到 Fabric loader + 模组数量；
   贴：导入摘要（文件数/字节/跳过数）、实例目录 `ls mods | wc -l`、启动日志的 mods loaded 行、截图
3. **env 过滤**：摘要里 `skipped(server-only)=N`，并与 index 里 `env.client=="unsupported"` 的条数一致
4. **CF zip**：用同一套代码导一个 CurseForge 整合包（没有 key 时走 curse.tools，日志标注 `via=curse.tools`），
   能启动或如实报告缺文件清单。**如果拿不到测试包**，就用合成 zip（包含 manifest.json + 2 个假 files）
   验证解析与三级策略，并在报告里写清"仅合成验证"
5. **重名**：同一个包导入两次 → 第二个 instanceId 递增，原实例文件不被改动（贴 diff 或 ls）
6. 共享根不变：导入前后 `du -sh <gameRoot>/assets <gameRoot>/libraries` 一致或只有增量，
   `find <gameRoot>/assets -newer <marker>` 的增量必须为 0
7. 截图：确认页 + 导入中 + 完成后的实例页，窗口级 grim + PID 断言
8. 报告：`文件:行`、`git log --oneline 20f8d7c..HEAD`（含第 0 步三修）、`git status --porcelain`、导入摘要原文

## 不要做

CurseForge 浏览/搜索、资源包与光影界面、新建空实例向导、删除实例、
P3 下载器与熔断改动、对 `~/Minecraft` 的写入。
