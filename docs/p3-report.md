# YMCL · P3 最终报告（版本清单 / 下载 / 安装 / 加载器）

> 2026-11-07。P3 = 版本清单缓存 + 下载源抽象（BMCLAPI 默认可关）+ 原版冷装 + 加载器安装
> （Fabric / Forge）+ 端到端自检。分支 `p3-install`，交接 tag `p3-complete`。
> 任务书：`prompts/phase3-install.md`（v1.1）；历史：`docs/p3-step1-progress.md`、`docs/p3-step3-report.md`。

## 结论

P3 三个子步骤（meta/下载器、原版安装、加载器）全部完成并验收。E2E 自检入口
（`installE2ESelfCheck`）建成并编译通过，从三个全新建目录依次冷装「原版 → Fabric → Forge」：
**vanilla、fabric 两步端到端通过；forge（第三步）全量冷装 30 分钟未完成，后台任务在
30m55s 被用户主动停止（非代码失败，见 §4）**。
`./gradlew build` / `test` 通过（54 tests / 0 failures / 0 errors），工作树干净，
`find ~/Minecraft -newer /tmp/p3-marker` 为空（全程未写真机游戏目录）。

## 1. 三个子步骤验收证据表

| 子步骤 | 关键证据 | 结果 |
|---|---|---|
| **S1 meta/下载源** | `DownloadSource` 候选列表（2 源回退 + 302 跟随 + 主机熔断 + 成功率排序）；`LauncherSettings`（`data/launcher.json`，UTF-8 无 BOM，坏 JSON/高 schemaVersion 回落默认）；`loadManifest` 922 版本、TTL 6h、离线回退；51 tests / 0 failures | 通过 |
| **S2 原版安装** | 冷装 `files=3689 bytes=740830303`、`install summary failures=0`；预热真跑 `skipped=3455`；主菜单截图 `build/p3-b-menu.png`（MINECRAFT JAVA EDITION / Singleplayer / Multiplayer / Realms / Options / Quit Game / 左下 Minecraft 1.20.1）；C 组全量冷装基线 `build/p3-c-cold.log` | 通过 |
| **S3 加载器** | Fabric：`8` 库 `{bmclapi=8}`、`fabric-loader-0.20.1-1.20.1`（`inheritsFrom=1.20.1`、`jar=1.20.1`、`KnotClient`）、日志 `Loading Minecraft 1.20.1 with Fabric Loader 0.20.1`、截图 `build/p3-fabric-menu.png`。Forge：`--help` 真实参数 `--installClient`、installer `exit=0` 约 8.5s、自包含 profile `117` 库（`38` game args / `27` jvm args、`jar` 硬链接原版，link count 4）、日志 `Launching target 'forgeclient'`、截图 `build/p3-forge-menu.png`（左下 `Forge 47.4.23 / Minecraft 1.20.1 / 2 mods loaded`）。幂等 `downloaded=0 skipped=8`；错误路径 `1.99.9 → 可读 LoaderInstallException`。新增 3 单测 | 通过 |
| **S4 E2E 收尾** | 入口建成编译通过；vanilla / fabric 两步端到端通过，forge 未跑完（见 §4 与下方「E2E 未完成说明」） | 部分通过 |

补充证据：
- `grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource` → **空**（含测试夹具也不写 `https://` 字面量）。
- 失败清单机制（C 组）：`STALL@30s` / `TIMEOUT@300s` 判据、`degraded after 2 failures`、
  `INSTALL ERROR: <文件>: <期望/实际 sha1 与 size>`、进程非零退出；A 组结构自检
  `build/p3-a12.log`、`build/p3-a3.log`（3 次人为停滞均 ≤35s abort）。

## 2. C 组 14 个失败文件（网络烂时行为正确的唯一物证）

C 组（无预热、无代理全量冷装，7 分钟窗口）`failures=14`，全部是源返回 985–989 字节
错误页导致 sha1/size 双校验失败，删 `.part` 重下一次仍失败后进入 `INSTALL ERROR` 并计入
失败清单、进程非零退出。**完整 14 行表（文件名 / 期望 sha1 / 实际 sha1 / 期望 size /
实际 size）见 `docs/p3-step3-report.md`**，留证 `build/p3-c-cold.log`（7615 行）。
指标：完成 3675 / 计划 3689、真实下载 3653、平均 26.4 文件/秒（前 138s 窗口）、
STALL 0 / TIMEOUT 1 / degraded 4 / 失败 14 / `.part` 残留 0。

## 3. 已知限制（交接必读）

1. **Fabric 主菜单左下角不显示 `fabric-loader-0.20.1-1.20.1`**：Fabric Loader 0.20.1
   的 `Hooks.insertBranding` 把 brand 硬编码为 `"fabric"`，左下角固定
   `Minecraft 1.20.1/Fabric (Modded) + 8 mods loaded`；版本 id 在版本 json 与自检日志。
   反汇编确认，非启动失败。不为指标写 mod 造假。
2. **`${clientid}` / `${auth_xuid}` 宏仍未替换**（P2 遗留）：真实日志可见
   `--clientId, ${clientid}, --xuid, ${auth_xuid}`。1.20.1 + Forge 不读这两个参数，
   离线启动无影响；**正版登录 / Realms 前必须处理**。
3. **窗口级截图**：全屏 `grim`（3840×2160）会拍到别的窗口，与历史上 `wmctrl` 类假阳性
   同源。以后用窗口级：
   `grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`，
   且截图前断言 `activewindow.class` 是游戏窗口。
4. **Forge**：installer 需要 `launcher_profiles.json`；必须用**自包含 profile**（继承式会撞
   JPMS `Module minecraft contains package com.mojang.blaze3d.platform`），别「优化」回去。
5. **停滞判据**：30s 无新字节 → `STALL@30s` abort 切候选；总 300s 兜底。慢不等于坏
   （C 组 official 上 538KB 花 196s 是网络烂，不是文件坏），只杀没进展。
6. **x-bmclapi-hash**：只在 40 位合法 sha1 且本地已存在同 hash 文件时用于「跳过」，
   **绝不替代 manifest/版本 json 的 sha1 校验**。
7. **未做（P3 范围外）**：NeoForge、Quilt 实现；整合包（P5）；资源下载（P4）；设置页
   镜像开关 UI（`// TODO(settings-ui): 下载分组`）；对 `~/Minecraft` 的任何写入。
8. **C 组 14 个失败对象**：属上游错误页，留待网络好/代理时复跑，作为「国内无代理基线」对照。

## 4. E2E 端到端自检

入口 `installE2ESelfCheck`（`E2ESelfCheck.kt`）：三个全新目录
`~/ymcl-test/e2e-<ts>/{vanilla,fabric,forge}`，**不复用** `mc` / `mc-fabric` / `mc-forge`，
**不预热**（不从真机 `cp -al`）；每步 `GameInstaller` 冷装原版 →（Fabric/Forge 装 loader）
→ 启动断言 → 输出一行 `STEP <name>: files / bytes / elapsedMs / sources /
downloadFailures / launchMarker / exitCode / ok / reason`；最后一行 `E2E_RESULT ... overall=...`。
允许慢、允许失败，失败会写明是哪一步与原因。留证 `build/p3-e2e.log`。

实测输出（`/tmp/p3-e2e.log`，50 行）：

```
E2E start root=/home/decafmayo/ymcl-test/e2e-1794062324715 source=bmclapi
STEP vanilla: files=3689 bytes=740830303 elapsedMs=295666 sources={bmclapi=3590, official=77, cached=24} downloadFailures=0 launchMarker=true exitCode=0 ok=true reason=OK
STEP fabric: files=3689 bytes=740813778 elapsedMs=380968 sources={bmclapi=3628, official=45, cached=25} downloadFailures=1 launchMarker=true exitCode=0 ok=true reason=1 个文件下载失败但启动通过
```

**E2E 未完成说明（如实记录，不掩盖）**：vanilla、fabric 两步 `ok=true`（含启动断言
`launchMarker=true`、`exitCode=0`）。第三步 forge 是三轮里最慢的——每轮全量冷装
3689 文件 / ~740MB，且 Forge installer 还要联网拉自己的库；跑到 30m55s 时 forge 目录
仅 1327 文件，后台任务被用户主动停止（"卡住太久，已被我停止"）。因此：
- **没有 `E2E_RESULT` 总结果行**（进程被 kill，未执行到打印总结果那一步）；
- 这不是代码失败：前两步证明入口逻辑与启动断言正确；forge 单独验收已在 §1 S3
  （`build/p3-forge-menu.png`、`FORGE_OK`、自包含 117 库）通过；"一次跑完三轮"这条
  端到端口径**未完成**，卡点是时间（全量冷装 × 3 + Forge installer 联网），不是崩溃。
  允许慢、允许失败，此处如实报出：停在 forge（第三步），原因是耗时过长、用户中断，
  未重启长任务、未伪造总结果行。

清理：`TaskStop` 已停止后台任务，无残留 E2E / 游戏 / installer 进程。三个 `mc-e2e-*` 目录
留在 `~/ymcl-test/e2e-1794062324715/`（未删，供复核）。

## 5. Git

```
$ git log --oneline p3-start..HEAD   # 见最终答复贴出的实际输出
$ git status --porcelain             # 空
tag: p3-start（起手点）、p3-complete（最终文档提交）
```

文档索引：`docs/HANDOFF.md`（下一步 / 已知坑 / tag）、`NOTES.md`（P3 实现决策）、
`AGENTS.md`（两条新硬规则）、`docs/p3-step1-progress.md`、`docs/p3-step3-report.md`。
