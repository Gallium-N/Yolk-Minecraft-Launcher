# YMCL 到 1.0 的施工顺序（P0 → P0.2 → P7 → P8 → YP）

> 一次只做一个阶段，每阶段结束**停下贴报告**，等确认再进下一阶段。
> 每个阶段一个 commit（大阶段可拆小步），每个 commit 独立 `./gradlew build` 通过。

## 0. 起手

```bash
cd ~/src/ymcl
git status --porcelain        # 必须为空
git switch -c release-1.0 620af59
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
```

## 已知事实（不要重新发现，直接信）

- 基线：`132` 个测试全绿；`./gradlew build --rerun-tasks` 是唯一可信的构建判据
- **打包运行时必须 `includeAllModules = true`**（或显式包含 `java.net.http jdk.crypto.ec jdk.charsets jdk.zipfs jdk.unsupported java.management jdk.localedata`）。少一个模块的后果是用户机器上一句 `Failed to launch JVM`，极难查。已踩过：`java.net.http` 缺失
- 截图一律**窗口级**：`grim -g "$(hyprctl activewindow -j | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`，截图前断言窗口 PID 属于本进程；**禁止**改 WM_CLASS 来"通过"断言
- 所有网络请求只走 `DownloadSource`；`grep -rn "https://" composeApp/src --include=*.kt | grep -v DownloadSource` 必须为空（测试夹具用 `http://`）
- `commonMain` 不许出现 `org.jackhuang`、`java.io/nio/awt`；替换文件用硬链接，跨设备回退复制
- 下载失败必须进失败清单并非零退出，**禁止静默跳过**；sha1 不匹配要**切下一个候选**（BMCLAPI 会改写 version json，sha1 天然对不上官方值）
- 禁止写 `~/Minecraft`；测试目录只用 `~/ymcl-test/<新目录>`

---

# P0 · 界面稳定性（约 30 分钟）

## 做

1. **主界面不许整页滚动**。删掉页面根容器上的 `Modifier.verticalScroll`（`InstancesPage.kt:183`、`GetContentPage.kt:133/375`、`DiagnosticsPage.kt:307`、`ImportPage` 同类）。滚动的只能是内容容器：实例表、搜索结果、日志区。高度用 `weight()` / `fillMaxHeight(fraction)` / `heightIn(min,max)`，不许写死 dp 把内容顶出边界。
2. **诊断页改成"开发中"占位**：居中一句话 + 一句说明，保留导航入口，删掉 P1 的假数据常量。P6 顺延，不要实现诊断逻辑。

## 验收

1. `build`/`test` 绿
2. 1100×800 与 1100×700 下，滚轮滚 10 格，截图前后 `compare -metric AE` = **0**
3. 实例页：滚轮只让表格内部滚，导航/工具条不动（前后截图）
4. `grep -rn "verticalScroll" composeApp/src/commonMain --include=*.kt` 每一处都能说明是"内容内部滚动"

---

# P0.2 · 入口、加载态、文案

## 做

1. **入口归位**：「获取内容」页两 tab —— **安装游戏版本** / 获取模组，默认第一个；「新建实例」从实例页移走；实例页只留「导入整合包」「刷新」与列表。
2. **Hero 引导**：无实例时点 Hero 的按钮直达「安装游戏版本」第一步，不要落到 mod 页。
3. **点击必须有反馈**：点版本行的瞬间 → 该行选中态 + spinner，**立刻**进下一步骨架（3 行占位，不是空白、不是"加载中…"）；拉 loader 列表 >8s → 可读错误 + 「重试」「返回」；切换版本要取消上一个请求。
4. **文案瘦身**：空状态一句话 + 一个按钮（≤20 字）；按钮 2–4 字；删掉所有 `你可以` `即可` `只需` `帮助你` `强大的` `丰富的`；错误只写"发生了什么 + 下一步"。
5. 加载态/错误条/空状态各只用一个组件，页面不许各写一套。

## 验收

1. `build`/`test` 绿
2. 截图：获取内容两 tab、实例页（无新建按钮）、空状态 Hero 三个入口
3. 连拍三张：点版本 → 选中态+spinner → 骨架 → loader 列表，全程无空白
4. 断网后点版本 → ≤8s 出现可读错误 + 重试（截图）
5. `grep -rn "你可以\|即可\|只需\|帮助你" composeApp/src/commonMain --include=*.kt` 为空

---

# P7 · Java 自动发现 / 自动选择 / 自动安装

## 做

1. **发现**（只读 `release` 文件，不执行 `java -version`）：`<appRoot>/java/*`、`$JAVA_HOME`、`PATH` 逐条回溯、`/usr/lib/jvm/*`、`~/.sdkman/candidates/java/*`；Windows 加 `Program Files\Java|Eclipse Adoptium|Microsoft\jdk-*|Zulu`。`JAVA_VERSION="1.8.0_412"` → 8。
2. **选择**（纯函数）：用户指定优先 → 矩阵（≤1.16→8、1.17–1.20.4→17、1.20.5+→21、未知→21）→ 精确匹配取最高补丁 → 就近更高 → 就近更低；输出 `JavaChoice(java, reason)`，日志 `Java selected: <path> (<vendor> <ver>) reason=<...>`。
3. **自动安装**：`api.adoptium.net/v3/assets/latest/<major>/hotspot?os=..&architecture=..&image_type=jre` 取 asset 名与 sha256；镜像 `{mirror}/Adoptium/<major>/jre/<arch>/<os>/<assetName>`，**17/21/25 走 TUNA 优先，8 走华为云优先**（TUNA 的 8u 实测 404）；sha256 不符删了重下；Windows zip / Linux tar.gz 两条解压路径；zip-slip 保护；剥单一顶层目录；落 `<appRoot>/java/<vendor>-<major>-<os>-<arch>/`，`install.json` 里**存相对 id**。
4. **UI**：设置页「Java 运行时」分组（列表、设为默认、打开目录、自动管理开关、安装指定主版本）；启动前缺 Java 弹窗：`《实例名》需要 Java 8，本机没有可用运行时` + 「自动安装（约 40MB）」/「手动选择」。

## 验收

1. `build`/`test` 绿；新增 ≥8 单测（release 解析、矩阵边界、五种选择含 reason、镜像首候选、sha256 重下、剥顶层+zip-slip、相对 id、去重）
2. 清空注册表重启 → 设置页列出本机 3 个 Java（截图）
3. 1.12.2 用 Java 8、1.20.1 用 21，各贴一行 `Java selected:`
4. **删掉注册表里的 Java 8 → 启动 1.12.2 → 弹窗 → 真实下载 → 进主菜单**（贴镜像 URL、大小、sha256、`install.json`）
5. `grep -rn "/home/decafmayo\|C:\\\\" <appRoot>/java/*/install.json` 为空
6. 取消安装无残留

---

# P8 · 打包分发

## 做

1. **图标**：`windows { iconFile.set(file("../design/logo/ymcl-logo.ico")) }`、`linux { iconFile.set(file("../design/logo/ymcl-logo-512.png")); menuGroup="Games"; appCategory="Game" }`；运行时窗口图标也要设（资源放 `composeResources/drawable/`）。先把 `design/logo` 分支 merge 进来。
2. **版本号**：`packageVersion` 读 `-Pymcl.version`，CI 用 `git describe --tags`；关于页显示 `v<ver> (<commit>)`。
3. **裁运行时**：`jdeps --print-module-deps` 得到模块集，手工补 `jdk.crypto.ec jdk.charsets jdk.zipfs jdk.unsupported java.management java.net.http jdk.localedata`，写进 `modules(...)`，**保留 `-Pymcl.allModules` 回退开关**。目标 234MB → ≤120MB。
4. **Windows 产物**：portable zip（已有）+ 7-Zip SFX 单文件 exe（`InstallPath=%LOCALAPPDATA%\YMCL\<ver>`，`RunProgram="YMCL.exe"`，文档写明首启解压 1–3 秒）。MSI 保留 `continue-on-error`。
5. **Linux**：`appimagetool` + `YMCL.desktop`（`Categories=Game;`）+ 256px 图标 → `YMCL-<ver>-x86_64.AppImage`。
6. **发布**：每个产物旁边 `SHA256SUMS`；推 `v*` tag 时建 Release，正文写清未签名会被 SmartScreen 拦。

## 验收

1. `build`/`test` 绿；CI 三个 job 全绿
2. 产物齐全：portable zip、SFX exe、AppImage、SHA256SUMS
3. **干净 Win10 VM**：SFX exe 双击 → 首启解压 → 窗口起来（图标正确）→ 建 1.20.1 → 进主菜单
4. VM 里 `where java` 为空的前提下跑通
5. 运行时裁剪前后字节对比
6. Linux AppImage 在 WS 上直接跑（截图）

---

# YP · YolkPack 离线迁移包

## 做

1. **导出**：实例页三点菜单「导出迁移包」+ 设置页「导出全部资源」。两种粒度：`instance-only`（只含实例目录与该版本 json）、`full`（加该版本**实际引用**的 assets/libraries，按 index 精确挑选）。`ZipOutputStream`，jar/zip/png 用 `STORED`；边写边算每文件 sha1 与总 sha256；**清单 `yolkpack.json` 最后写入**；超 2GB 按 500MB 分卷。
2. **导入**：读清单 → 预检报告（将写入文件数/字节、已存在同 sha1 跳过数、冲突列表、磁盘检查）→ 解压写入 `gameRoot`（拒绝 `..`/绝对路径/盘符/符号链接；**跳过并记录** `.sh/.bat/.exe`；解压总量超声明 10% 中止）→ 每文件校验 sha1，不符进失败清单 → 实例重名 `-2` 递增 → 摘要 `YolkPack imported: files=.. bytes=.. skipped=.. failed=..`。
3. 导入全程**不需要网络**。

## 验收

1. `build`/`test` 绿；新增 ≥7 单测（清单解析、zip-slip、可执行文件跳过、sha1 不符进清单、重名递增、分卷顺序、超量中止）
2. 导出真机：1.20.1+Fabric 的 full 包，贴体积/文件数，与 `du -sh game/assets game/libraries` 对比
3. **离线导入真机（核心）**：空 `gameRoot` + `DownloadSource` 指向不可达地址 → 导入 → 实例出现 → **不联网进主菜单**（贴日志与截图）
4. 预检报告截图（跳过/冲突/磁盘三项都有数字）
5. 手工破坏包内一个文件 → 该文件进 failed，其余成功，非零退出

---

# 全局红线

不改 P3 传输层的超时/熔断参数；不改已验收的下载语义；不写 `~/Minecraft`；
不做登录认证、服务端、云同步；界面中文、代码与 commit 英文；
每个阶段结束停下贴报告（`文件:行` + `git log --oneline` + `git status --porcelain` + 验收原样输出）。
