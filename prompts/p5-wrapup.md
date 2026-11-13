# P5 收尾：整合包这条路真正闭环

> 任务书只读。commit：`test(modpack)` 端到端、`fix(dnd)` 或说明、`docs(p5)` 收尾。
> 每个 commit 独立 `./gradlew build` 通过。

## 为什么要补

P5 的功能提交都在（mrpack 真机导入通过），但三件事没做：

1. **Forge / NeoForge 整合包的端到端启动**从未验证——当时 `LoaderInstaller` 只有 Fabric，
   P3 v2 补了 Forge/Quilt/NeoForge，但**没人回到整合包这条路径上复验**
2. **拖放 zip** 只有代码，没有真实拖拽
3. **没有 `docs/p5-report.md`**，且 `p5-complete` tag 打在了 P3 的任务书提交上

## 1. Forge 整合包端到端（最重要）

用**合成 mrpack**，明确控制变量：

```json
{
  "formatVersion": 1, "game": "minecraft", "versionId": "1.0.0",
  "name": "Forge Smoke Test",
  "dependencies": { "minecraft": "1.20.1", "forge": "47.2.0" },
  "files": [
    { "path": "mods/jei-1.20.1-forge.jar", "fileSize": <真实值>,
      "hashes": { "sha1": "<真实值>", "sha512": "<真实值>" },
      "env": { "client": "required", "server": "required" },
      "downloads": ["<Modrinth 上 JEI 的 forge 版文件 URL>"] }
  ]
}
```

- JEI 的 forge 版文件用 Modrinth API 查（`loaders=["forge"]`、`game_versions=["1.20.1"]`），
  拿真实的 url / sha1 / size，**不要编**
- 导入 → 实例出现 → 启动 → 必须进主菜单，日志有：
  - `Java selected: ... reason=exact match`（或 nearest）
  - `Launching target 'forgeclient'`
  - Forge 版本行 `47.2.0`
- 截图：导入确认页、主菜单（左下角能看到 Forge 47.2.0）

**失败也算结果**，但要定位到具体环节：是 base 安装、loader 安装、还是 files 下载。
不许用"环境问题"四个字带过。

## 2. NeoForge 整合包（条件项）

先探测 `maven.neoforged.net`：

```bash
curl -sI -m 20 https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml | head -1
```

- 可达 → 用 1.21.1 的 NeoForge 整合包（合成，dependencies 里 `neoforge: "21.1.x"`）跑一遍端到端
- 不可达 → 在报告里记 `❌ 网络不可达（贴 curl 结果）`，**不要**为了过验收去改代码绕过

## 3. 拖放真机验证

`DesktopMain:149` 的 `DropTarget` 要真跑一次拖拽。三种做法，按可行性选：

1. **首选**：在 XWayland 窗口上用 `python-xlib` 发一次 Xdnd 拖放（写 20 行脚本，模拟 `XdndEnter/Drop`），
   断言导入向导弹出且文件名正确。做成 `tools/xdnd-drop.py`，跑完留日志
2. 做不到 → 用 `Xephyr` 开一个嵌套 X 会话跑 YMCL，在 X 里用标准 DnD 工具发事件
3. 都做不到 → 标记 `⚠️ 代码存在、未真机验证`，并在报告里写清试过哪几种、卡在哪

**不许**用"调用导入函数"冒充拖放验证——那验的是函数不是拖放。

## 4. CurseForge 真包

- 你若能手动下载一个 CurseForge 整合包 zip（浏览器，不需要 API key），放到
  `~/ymcl-test/packs/cf/` 下，用它真导一次
- 没有包 → 保持 `❌ 未验证（环境：curse.tools 被 Cloudflare 403、无 API key）`，
  在报告里写明"代码路径已由 3 个单测覆盖，但无真包证据"

## 5. 文档收尾

- `docs/p5-report.md`：范围、两种格式实现要点、CF 三级策略、**验收证据表**（含本轮四条）、
  已知限制（CF 无 key、拖放验证方式、NeoForge 网络）
- `docs/HANDOFF.md` §4：P5 段落改成"已完成（证据见 docs/p5-report.md）"或"部分完成 + 具体缺口"，
  **不许**在没证据的情况下写"已完成"
- §8 tags：新增 `p5-v2`（`p5-complete` 保留不动，指向旧提交是历史事实，不要移动它）
- `docs/p3-capability-matrix.md`：把"整合包 Forge 端到端"这一行补上（本轮结果）

## 验收（缺一不算过）

1. `build`/`test` 绿；三个 commit 各自通过
2. **Forge 整合包**：导入摘要、启动日志三行（Java / forgeclient / Forge 47.2.0）、主菜单截图
3. **NeoForge**：`curl` 探测结果 + 端到端或 `❌` 结论
4. **拖放**：真发一次事件的日志/截图，或 `⚠️` + 试过的方案清单
5. **CF**：真包导入结果，或 `❌` + 环境证据
6. `docs/p5-report.md` 存在且含证据表；HANDOFF §4 的 P5 措辞与实际证据一致
7. `git tag --list | grep p5` 显示 `p5-complete` 与 `p5-v2` 两个
8. 报告：`文件:行`、`git log --oneline 2ed7b6a..HEAD`、`git status --porcelain`

## 不要做

改 P3 传输层、改 JVM 选择逻辑、改动效、加新功能、写 `~/Minecraft`。
