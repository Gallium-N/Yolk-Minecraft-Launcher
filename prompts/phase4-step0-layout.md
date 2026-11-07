# P4 第 0 步：目录模型落地（Versions + 版本隔离 + 共享 assets/libraries）

> 任务书只读输入，不要改本文件。分两个 commit：`feat(layout)` + `test(layout)`，
> 每个 commit 独立 `./gradlew build` 通过。

## 为什么先做这步

P3 的 E2E 跑不完，根因不是网络：**每个实例目录各带一份 `assets/` + `libraries/`**，
装第二个 1.20.1 要再下 740MB。同样的文件、同样的 sha1，重复下载是产品级缺陷。

我们的引擎是 HMCLCore，它按 `versions/<versionId>` 组织版本、从游戏目录根解析
`assets`/`libraries`。所以不能照搬 Prism 的 `instances/<name>/minecraft/`，要落成
**HMCL 的版本隔离模式**——这正是国内用户熟悉的那套。

## 目标结构

```
<gameRoot>/                        ← LauncherSettings.gameRoot，默认 <appRoot>/data/game
├── assets/                        ← 全局唯一，所有版本共享
├── libraries/                     ← 全局唯一，所有版本共享
├── launcher_profiles.json         ← Forge installer 需要，见 P3 已知坑
└── versions/
    └── <instanceId>/              ← 一个实例 = 一个版本目录
        ├── <instanceId>.json      ← 版本 json（原版 / fabric-loader-… / forge…）
        ├── <instanceId>.jar       ← 原版 jar 或 "jar" 指向被继承版本
        ├── instance.json          ← 可移植配置（displayName、loader、schemaVersion）
        ├── machine.local.json     ← 绑本机（java、xmx）
        ├── mods/  config/  saves/  resourcepacks/  shaderpacks/  logs/
        └── natives/
```

启动参数要点（写进日志，便于验收）：

- `--gameDir <gameRoot>/versions/<instanceId>`（隔离模式）
- `--assetsDir <gameRoot>/assets`
- 库路径一律相对 `<gameRoot>/libraries` 解析，**不允许**出现实例目录下的 libraries
- `-Djava.library.path=<实例>/natives`

## instanceId 与 displayName 必须分开

- `instanceId` = 目录名，由 displayName **生成一次后固定**，之后改 displayName 不改目录
- 生成规则：ASCII 字母数字与 `-` `_` 保留；其它字符（含中文）折叠为 `-`；
  折叠后为空或只剩分隔符 → 用 `mc-<mcVersion>-<n>`（n 从 1 递增取第一个不冲突的）
- 套用已有规则：拒绝 `< > : " / \ | ? *`、控制字符、结尾空格/点、Windows 保留名
  （CON/PRN/AUX/NUL/COM1-9/LPT1-9，大小写不敏感）、总路径 < 240 字符
- 冲突：`versions/mc-1.20.1-1` 已存在 → 递增到 `-2`，不许覆盖已有实例

## 非隔离兼容（必须做，但不许默认）

- `LauncherSettings` 增 `isolatedInstances: Boolean = true`
- 隔离开：`--gameDir` 指实例目录（如上）
- 隔离关：`--gameDir` 指 `<gameRoot>`，mods/saves/config 都在根——给老整合包和
  "多个版本共用一套 mods" 的目录用
- **检测**：导入/打开实例时若发现 `<gameRoot>/mods/` 非空，提示
  "这是共享 mods 的老目录，仍按根目录运行"，并且该实例强制走非隔离
- 不允许把根目录的 mods 搬进实例目录；不许删除任何用户文件

## 改动范围（预期会碰到的文件）

| 文件 | 改什么 |
|---|---|
| `app/LauncherSettings.kt` | 增 `gameRoot`、`isolatedInstances`；缺字段回落默认，不重写文件 |
| `app/GameInstaller.kt` | 落点改为 root 的 assets/libraries + `versions/<id>/` |
| `app/GameDownloader.kt` | 目标路径参数化（root 共享 vs 实例私有），校验逻辑不动 |
| `app/LoaderInstaller.kt` | 版本目录名 = instanceId；Fabric 的 `jar` 继承与 Forge 自包含保持现状 |
| `platform/*` | 路径解析、硬链接/复制策略；跨设备（EXDEV）时回退复制 |
| `domain/InstanceId.kt`（新） | slug 生成与校验，纯函数，禁止 java.io |

## 单测（≥6，夹具不联网）

1. 中文 displayName → 可用的 instanceId，且不为空
2. 保留名（`CON`、`com1`）与结尾点/空格被拒
3. 重名 → 递增到 `-2`，不覆盖
4. 隔离模式解析出 `--gameDir=<实例>`，非隔离模式解析出 `--gameDir=<root>`
5. root 下 `mods/` 非空时判定为非隔离
6. 第二次安装同版本：全部 `skipped/cached`，下载字节 ≈ 版本 json 大小

## 验收（缺一不算过）

1. `build` / `test` 通过；新增单测 ≥6
2. **装 1.20.1 两次**（两个不同 displayName）：
   - 第一次：下载 ~740MB
   - 第二次：`downloaded=0`（或只有版本 json），`skipped≈3689`，贴计数
   - `du -sh <root>/assets <root>/libraries` 只有一份；`ls <root>/versions` 两个目录
3. 装 Fabric 到第三个实例：只下 8 个库，assets/libraries 无新增
4. 启动断言：日志里 `--gameDir` 指向实例目录、`--assetsDir` 指向 root；
   隔离实例的 `mods/` 在实例目录下，root 的 `mods/` 不存在或为空
5. 非隔离路径：手工在 root 建 `mods/x.jar`，启动 → `--gameDir=<root>`，且有提示日志
6. **E2E 重跑**（原版 + 原版副本 + Fabric，三个空目录）：墙钟 ≤ 5 分钟，
   打印 `E2E_RESULT ok=true steps=3`。这条通了，才说明这步真的解决了问题
7. 截图用**窗口级**：`grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`，
   截图前断言 `activewindow.class` 是游戏窗口
8. 报告：`文件:行`、`git log --oneline p4-start..HEAD`、`git status --porcelain`、
   两个 `du -sh` 与两次安装的计数

## 不要做

Modrinth / CurseForge 代码、UI 改动、NeoForge、整合包导入、对 `~/Minecraft` 的任何写入。
也不要"顺手优化"P3 已通过的下载器超时/熔断/失败清单逻辑——那套现在是验收过的基线。
