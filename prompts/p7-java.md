# P7：Java 自动选择 + 自动安装

> 任务书只读。commit 切分：`feat(java)` 扫描与选择、`feat(java)` 安装器、`feat(ui)` 设置页与启动前弹窗、`test(java)`。
> 每个 commit 独立 `./gradlew build` 通过。

## 目标

P3 只做到「按矩阵在**已注册**的 Java 里挑一个」。现在要补完最后一公里：

1. **自动发现**：用户机器上已有的 Java 不用手动加
2. **自动选择**：给出选择理由，可解释、可覆盖
3. **自动安装**：一个都没有时，直接从国内镜像装一个合适的 JRE 并注册，用户只需点一次确认

## 1. 发现（`platform/JavaScanner.kt`）

扫描顺序，全部只读、不执行 `java -version`：

| 来源 | 位置 |
|---|---|
| 启动器自装 | `<appRoot>/java/*/release`（我们自己写的 `install.json` 优先） |
| `JAVA_HOME` | `$JAVA_HOME/release` |
| PATH | 逐条 `PATH` 找 `bin/java`，回溯到根读 `release` |
| Linux | `/usr/lib/jvm/*/release`、`/usr/java/*/release`、`~/.sdkman/candidates/java/*/release` |
| Windows | `%ProgramFiles%\Java\*`、`%ProgramFiles%\Eclipse Adoptium\*`、`%ProgramFiles%\Microsoft\jdk-*`、`%ProgramFiles%\Zulu\*`、`%LOCALAPPDATA%\Programs\Eclipse Adoptium\*` |

读 `release` 文件取 `JAVA_VERSION="21.0.13"`，解析出主版本（`1.8.0_x` → 8）。

- 结果去重（同一 `java` 可执行文件算一个），按路径归一化
- 扫描结果缓存到内存，设置页有「重新扫描」按钮
- 扫描失败（某个目录不可读）只记日志，不整体失败

## 2. 选择（`app/JavaSelector.kt`，纯函数）

输入：实例的 `mcVersion`、已注册 Java 列表、用户手动指定的 Java（可空）。

规则，**每条都要能解释**：

1. 用户手动指定 → 直接用（即使不匹配矩阵，也只 warning）
2. 按矩阵求需要的主版本：≤1.16→8、1.17–1.20.4→17、1.20.5+→21、未知→21
3. 精确主版本匹配里取**补丁号最高**的
4. 没有精确匹配 → 取**最近的更高主版本**（如需要 17 只有 21）；再没有 → 最近的更低主版本
5. 输出 `JavaChoice(java, reason)`，`reason` 形如
   `exact match 21.0.13 (Temurin)` / `nearest newer 21 (requested 17)` / `user override`

`JavaChoice.reason` 必须打进启动日志：`Java selected: <path> (<vendor> <ver>) reason=<reason>`。

## 3. 自动安装（`app/JavaProvisioner.kt`）

只在「选择失败（没有任何 Java）」或用户点「安装 Java」时触发。

1. **API**：`GET https://api.adoptium.net/v3/assets/latest/<major>/hotspot?os=<linux|windows>&architecture=<x64|aarch64>&image_type=jre`
   → `[0].binary.package.name`（asset 名）、`.checksum`（sha256）、`.link`（GitHub，最后兜底）
   - `GET https://api.adoptium.net/v3/info/available_releases` 取可用主版本，**不要硬编码版本表**
2. **镜像**（按实测策略，前缀替换即可）：

   ```
   {mirror}/Adoptium/<major>/jre/<arch>/<os>/<assetName>
   ```
   - 17/21/25：TUNA 优先 → 华为云 → API link
   - 8：**华为云优先**（TUNA 的 8u 实测 404）→ TUNA → API link
   - 404/5xx/超时按候选列表切换，日志标明用了哪个镜像
3. **下载**：流式 + 进度 + 可取消 + `Range` 续传（复用 P3 传输层语义），落到 `<appRoot>/tmp/java/`
4. **校验**：sha256 必须与 API 给的值一致，不一致删掉重下一次，再失败报可读错误
5. **解压**：zip（Windows）/ tar.gz（Linux）**两条路径都要实现**，zip-slip 保护，剥掉单一顶层目录
6. **落盘**：`<appRoot>/java/<vendor>-<major>-<os>-<arch>/`（如 `temurin-21-linux-x64`）
7. **注册**：写 `install.json`（vendor / major / fullVersion / sha256 / installedAt / source=major），
   路径存**相对 app 根的 id**，不存绝对路径（便携要求）
8. 安装完立即出现在设置页列表里，并被下一次选择命中

## 4. UI

**设置页新增「Java 运行时」分组**：

- 已安装/已发现列表：`Temurin 21.0.13 · /usr/lib/jvm/... · 已注册`，未注册的显示「注册」按钮
- 每行可「设为默认」「打开目录」
- 顶部「自动管理 Java」开关（`LauncherSettings.autoManageJava`，默认开）
- 「安装 Java 版本…」→ 选主版本（8/17/21/25，来自 available_releases）→ 权限说明（体积/镜像）→ 安装进度

**启动前缺 Java 时**：

- 弹窗：「《实例名》需要 Java 8，本机没有可用的运行时」+ 两个按钮：
  「自动安装（约 40 MB，来自华为云镜像）」「手动选择…」
- 自动安装期间不阻塞主界面，完成后自动继续启动
- 失败：可读错误 + 「重试」「手动选择」两个出口

## 5. 单测（≥8，离线，假 fetcher/假文件系统）

1. `release` 解析：`JAVA_VERSION="1.8.0_412"` → 8；`"21.0.13"` → 21
2. 矩阵边界：1.16.5→8、1.17→17、1.20.4→17、1.20.5→21、未知→21
3. 选择：精确匹配优先、补丁号最高、就近更高、就近更低、用户覆盖（五种各一条，断言 reason 文案）
4. 镜像策略：major=8 首候选是华为云；major=21 首候选是 TUNA
5. sha256 不匹配 → 删掉重下一次 → 仍失败抛可读错误
6. 解压剥顶层目录 + zip-slip 拒绝
7. 路径以相对 id 存储（不可出现 `/home/` 或 `C:\`）
8. 已注册的 Java 不被重复扫描（去重）

## 6. 验收（缺一不算真过）

1. `build`/`test` 绿，新增 ≥8；三项 grep 空
2. **发现**：清空注册表后重启启动器 → 设置页自动列出本机 3 个 Java（`/usr/lib/jvm/*` 与 sdkman），
   路径与版本正确（截图）
3. **选择**：1.12.2 实例启动 → 日志 `reason=exact match 8`（或 nearest），用 Java 8；
   1.20.1 实例 → 用 21。两条都贴日志
4. **自动安装**：删掉 `build/jdks` 的测试 JRE 并把注册表里 Java 8 移除 →
   启动 1.12.2 → 触发弹窗 → 自动安装 → 从镜像真实下载 → 启动进主菜单。
   贴：所选镜像 URL、文件大小、sha256 校验行、`install.json` 内容、启动日志
5. **便携**：`grep -rn "/home/decafmayo\|C:\\\\" <appRoot>/java/*/install.json` 为空
6. **Windows 路径**：无法真机验证时，用单测覆盖 zip 解压分支并标注 ⚠️
7. **取消**：安装中取消 → 临时文件清掉、无残留目录、可重来
8. 截图：设置页 Java 分组、缺 Java 弹窗、安装进度、安装完成
9. 报告：`文件:行`、`git log --oneline 2ed7b6a..HEAD`、`git status --porcelain`、上述原样输出

## 不要做

登录/正版认证、Java 版本升级提示、批量修复所有实例的 Java、P3 传输层参数改动、
对 `~/Minecraft` 的写入、删除已安装的 Java（先不做，危险）。
