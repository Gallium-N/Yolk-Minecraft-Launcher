# YMCL — 项目状态（下次继续看这里）

更新时间：2026-11-03

## 一句话定位

**Yolk Minecraft Launcher** —— 自用 + 几个朋友的 Minecraft 启动器，Windows/Linux 双平台，
无后端、不联网同步、不上传数据。界面 Material 3，长项是**崩溃后能说清为什么**。

## 已定决策

| 项 | 决定 |
|---|---|
| 名字 | YMCL（Yolk Minecraft Launcher），包名 `dev.mayo.ymcl` |
| 平台 | Windows + Linux（桌面双平台） |
| 后端 | 无。纯本地，GitHub Release 分发 |
| 引擎 | HMCLCore（Kotlin，GPL-3.0 → 项目必须开源），只重写 UI 层 |
| UI | Kotlin + Compose Multiplatform（`androidx.compose.material3` 原生 MD3） |
| 风格 | Standard 骨架 + Expressive 圆角/动效；只有 Hero 卡夸张 |
| 布局 | 左侧 88 导航栏 + 右侧内容（1280×800 基准） |
| 整合包 | 标准 mrpack；自定义配置放 `client-overrides/.launcher/instance.json`，没它也能装 |
| 配色 | 种子色 `#EFDBB2`（暖金，MTB 导出），亮/暗两套 49 个角色 |

## 文件在哪

```
~/src/ymcl/
├── NOTES.md                     ← 本文件
├── design/
│   ├── tokens/
│   │   ├── ymcl-dark.json       ← Penpot 可导入的 DTCG（49 token）
│   │   └── ymcl-light.json
│   └── mockups/
│       ├── ymcl-{home,instances,diagnostics,settings}-{dark,light}.svg
│       ├── ymcl-{...}-{dark,light}.png
│       └── preview.html         ← 四屏 × 亮暗一页看完
└── tools/
    ├── mtb2penpot.mjs           ← MTB 导出 JSON → DTCG（node，无依赖）
    └── gen_mockup.py            ← 从 tokens 生成四屏 SVG + 预览 HTML
```

重新生成：`python3 ~/src/ymcl/tools/gen_mockup.py ~/src/ymcl/design/tokens ~/src/ymcl/design/mockups`
（需要 tokens 目录里的 `dark.json` / `light.json`。若文件是 `ymcl-*.json`，先改脚本里的 load() 或复制一份改名的。）

## 四屏规格（正文数字，写规格时直接用）

通用：1280×800；导航栏宽 88（`surfaceContainer`）；内容区 x=112，宽 1144；页边距 24。

### 启动台（home）
- Hero 卡 1144×240，圆角 24，`primaryContainer`→`tertiaryContainer` 对角渐变
  - 「主实例」小标签 11px；标题 36px；四项统计（标签 11 / 值 15）：游戏版本 / 模组加载器 / 总游玩 / 上次启动
  - 按钮组：齿轮 48 圆（次级，改实例设置）+ 启动 72 圆（primary）
- 下半两列：左 620 / 间距 24 / 右 500
  - 左：最近实例 2 张卡（620×144，圆角 24，间距 16）+「所有实例」条（620×56，圆角 16，`surfaceContainerHigh`）
  - 右：「获取内容」面板 500×372，四行各 84：下载游戏版本 / 打开实例文件夹 / 浏览模组与整合包 / 导入本地整合包

### 实例（instances）
- 工具条 y=88 高 44：搜索框 360 宽 +「最近启动 ▾」132 +「新建实例」148（primary）
- 表格容器 1144×504，行高 92；六列 x 偏移（相对内容区）：实例 88 / 版本·加载器 300 / 总游玩 560 / 上次启动 720 / 状态 880 / 操作 1040
- 状态标签 92×26：运行中 `primaryContainer`、就绪 `secondaryContainer`、可更新 `tertiaryContainer`

### 诊断（diagnostics）—— 产品卖点
- 左 700 / 右 420
- 结论卡 700×196：可能原因（11px 标签）→ 结论 22px×2 行 → 置信度 chip + 证据/时间/加载器
- 动作按钮 y=300 高 44：禁用 XX（primary）/ 降级 YY → 版本（`surfaceContainerHigh`）/ 复制诊断报告（描边）
- 其他线索卡 700×268：3 条，每条 = 圆点 + 标题 13 + 说明 12 + 右侧来源（等宽 10）
- 右栏原始堆栈 420×608：等宽 10.5，行距 22，冲突 3 行用 `secondaryContainer` 0.55 高亮；下半「自动定位」把类名映射到 jar
- 底部：保存为 Markdown / 重新分析 / 规则库版本

### 设置（settings）
- 左子导航 240（项 44 高，圆角 12，选中 `secondaryContainer`）：外观 / 游戏 / 启动 / 下载 / 关于
- 右 880 卡片：主题（分段 320×40）/ 从壁纸自动取色（开关 + 5 色点 + 种子色）/ 减少动效（开关）/ 界面密度（分段 220×40）
- 后三组（游戏 / 启动 / 下载 / 关于）**还没画**，等结构确认后再填

## 设计系统要点（避免下次重新推）

- 圆角：卡片 24 / 内嵌条 16 / 列表项 12 / 按钮全圆
- 间距只用 4 / 8 / 16 / 24 / 32
- 字阶：displaySmall 36 / titleLarge 22 / titleMedium 16 / bodyMedium 14 / labelMedium 12 / labelSmall 11
- 层次：页面 `surface`，卡片 `surfaceContainerLow`，内嵌/悬停 `surfaceContainerHigh`，导航栏 `surfaceContainer`（用 Low 会和浅色页面糊在一起）
- 图标：真实实现用 Material Symbols（`rocket_launch`/`folder_open`/`healing`/`settings`/`more_vert`），稿里是手画近似形状

## 架构决定（2026-11-04 讨论，待写进 dsh 规格）

- **单 JVM 单代码库**：Kotlin + Compose Multiplatform，只出 `jvm("desktop")` 一个 target。
  UI/领域/平台差异全部在一个 Gradle 工程里，Windows 与 Linux 跑同一份字节码。
- **分层**：`ui`(Compose) / `app`(用例) / `domain`(纯 Kotlin，禁止 java.io) /
  `platform`(expect-actual) / `engine`(HMCLCore 适配层，用接口包起来)。
- **配置分两层**（跨 OS 的关键）：
  - `instance.json` = 可移植（版本、加载器、模组清单、显示名、schemaVersion）
  - `machine.local.json` = 本机（Java 路径、Xmx、窗口尺寸、GPU 偏好）
  实例目录整体拷到另一台/另一个系统即可用，机器相关的绝不混进去。
- **路径规则**：内部一律 `/`，只在边界转换；比较路径前按平台决定是否忽略大小写；
  实例名禁止 `< > : " / \ | ? *` 与 Windows 保留名（CON/PRN/AUX/NUL/COM1…）；
  实例根目录保持浅，规避 Windows 260 字符上限。
- **编码**：读取一律 UTF-8 优先、失败回退 CP936（中文 zip 里的文件名、GBK 日志）；
  写出的 JSON 无 BOM、LF。
- **Java 运行时**：启动器自身的 JRE 由 jpackage + jlink 打包（用户不用装 Java）；
  游戏用的 Java 按平台+架构从 Mojang runtime manifest 取，不随包分发。
- **进程**：启动游戏用独立进程 + 日志流；Windows 用 Job Object 杀进程树，
  Linux 用进程组；Windows 上游玩中 jar 被锁 → 更新类操作延后到退出后。
- **打包/CI（2026-11-04 决定：不做安装包，做单文件）**：
  - Windows → **单个 .exe**：`createDistributable` 出 app-image 目录，再包成自解压单文件
    （先 7-Zip SFX，后换成自写 Rust launcher）；MSI **不做**
  - Linux → **AppImage**（由同一个 app-image 用 appimagetool 打包），同样单文件
  - GitHub Actions matrix（windows-latest + ubuntu-latest），两平台同版本号
- **便携优先（2026-11-04 改：解压到 exe 同级 `.YMCL/`，不用 %APPDATA%）**：
  ```
  <exe 所在目录>/.YMCL/
  ├── runtime/<version>-<sha8>/         自解压出的运行环境（含 jlink JRE）
  ├── java/<vendor>-<ver>-<os>-<arch>/  一键安装的 Java（带 install.json）
  ├── data/                             instances、launcher.json、logs
  └── tmp/                              下载与解压的临时区
  ```
  - 首启做一次可写探测；目录只读（Program Files、只读 U 盘）就回退 `%LOCALAPPDATA%\YMCL` 并提示
  - Windows 上点目录不算隐藏属性 → 首启 `attrib +h` 藏起来（第三方工具别乱翻）
  - 卸载 = 删 exe + 删 `.YMCL`；整个目录拷到别处即可迁移
    → 因此 **javaPath 必须存相对 `.YMCL` 的相对路径**，不能存绝对路径
- **自解压布局**：解压到 `.YMCL/runtime/<version>-<sha8>/`，新旧版本可共存，
  下次启动清理旧目录（解决 Windows 上"运行中不能覆盖自身"）；
  解压后校验 SHA256，防半截文件；首启静默 + 进度。
- **杀软误报**：单 exe 自解压 + 拉 JVM 是误报高发模式，最好做 Authenticode 签名，
  未签名就在发布页给 SHA256；Rust 启动器比 7z SFX 误报少。
- **升级机制**：签名清单 + SHA256 + 原子替换；Windows 运行中不能覆盖自身 → 退出后替换。
- **许可证**：GPL-3.0-or-later（HMCL 是 GPLv3 + §7 附加条款：修改版必须改名区分、
  不得移除版权声明 → 关于页保留 HMCL/Compose 版权）。个人使用不触发义务，给朋友=分发，
  源码可得即可（给仓库链接）。
- **升级预留**：① 所有持久化文件带 `schemaVersion` + 迁移器；② 诊断规则/下载源/Java 清单
  全部外置成数据（YAML/JSON），改规则不用发版；③ 第一天就做 i18n，别硬编码中文。

## Java 运行时安装（功能设计；镜像实测于 2026-11-04，从服务器 curl）

镜像可用性（Adoptium 目录 `/Adoptium/{major}/{jre|jdk}/x64/{windows|linux}/{asset}`）：

| 源 | 11/17/21/25 | Java 8 | 备注 |
|---|---|---|---|
| **TUNA** `mirrors.tuna.tsinghua.edu.cn/Adoptium` | ✅ 200 | ⚠️ 目录在、`8u512` 文件 404（镜像滞后） | **首选**，速度最好 |
| **华为云** `mirrors.huaweicloud.com/adoptium` | ✅ 200 | ✅ 200 | Java 8 与兜底都用它 |
| NJU `mirror.nju.edu.cn/adoptium` | 302（需跟随，落点未验） | — | 备选 |
| USTC `mirrors.ustc.edu.cn/adoptium` | 403（挡 curl UA） | — | 不依赖 |
| 阿里云 / 腾讯云 | 404 | 404 | 没有 Adoptium 镜像 |

- **版本发现**：`https://api.adoptium.net/v3/assets/latest/{major}/hotspot?os={windows|linux}&architecture=x64&image_type={jre|jdk}`
  实测可达，直接返回 `binary.package.name` + `checksum`（sha256）。
- **下载模板**：`{mirror}/Adoptium/{major}/{image}/x64/{os}/{assetName}`（TUNA 与华为云同结构）。
- **体积实测**：JRE 21 Windows **47 MB** / JDK 21 Windows **196 MB** / JDK 21 Linux **198 MB**
  → 默认安装 JRE（游戏只要 JVM），设置里给"完整 JDK（含 javac）"选项。
- 校验一律用 API 返回的 sha256，不依赖镜像是否附带校验文件。
- 解压：防 zip-slip、去掉压缩包顶层目录、写入 `install.json`
  （vendor/major/version/sha256/installedAt），`javaPath` 记相对 `.YMCL` 的路径。
- **MC 版本 → Java**：≤1.16 用 8；1.17~1.20.4 用 17；1.20.5+ 用 21；新快照 25。可在设置里覆盖。
- 界面位置：设置 → 游戏 → 「Java 运行时」卡片（已安装列表 + “安装 Java” 对话框：
  版本/镜像/体积 + 下载进度 + 校验结果）。

## 响应式规则（2026-11-04 补，解决"窗口只能等比缩放"）

画布组合：**1100×800（最小）/ 1280×800（基准）/ 1600×800（宽）**，文件在
`design/mockups/ymcl-{page}-{WxH}-{mode}.svg|png`，`preview.html` 三档并排。

- 内容宽 `W_c = 窗口宽 − 88(导航) − 48(左右留白)`；1280 时为 **1144**。**不设 max-width**，
  表格和卡片都吃满；窄于 1100 不设计（最小窗口约束住）。
- **启动台**：右列 `RW = clamp(400, 520, 0.437·W_c)`（1280→500），左列吃剩余（1280→620）；
  Hero 四项统计间距 `STAT_DX = clamp(170, 210, (W_c−300)/4)`（1280→210）。
- **实例表**：`Δ = W_c − 1144`，列偏移（相对内容区）：
  实例 `88`（固定）/ 版本·加载器 `300 + 0.10Δ` / 总游玩 `560 + 0.30Δ` /
  上次启动 `720 + 0.45Δ` / 状态 `880 + 0.66Δ` / 操作 `W_c − 104`（**永远贴右**）。
- **诊断**：右栏 `RW = clamp(340, 480, 0.367·W_c)`（1280→420），左栏吃剩余；
  三个动作按钮按 `33.7% / 36% / 剩余` 分配，别写死 236/252/180。
- **设置**：左子导航固定 240，右侧卡片列拉伸。
- **高度**：表格容器 = `窗口高 − 220`（吃满，底部留汇总行）；Hero 固定 240，
  窗口高 < 760 时降到 200（未出图，实现时按此）。
- **实现要点**：Compose 里根布局不要写 `Modifier.width(1144.dp)`，一律
  `fillMaxWidth()` + `weight()` + `widthIn(min=…)`；坑就在"算了一次宽度然后固定"，
  一旦固定，缩放时整个窗口看起来就是等比缩放的贴图。
- 窄宽度下的等宽堆栈区：折行或水平滚动，**不要**让它溢出面板。

## Phase 计划（按顺序，每个 Phase 一份 `prompts/phaseN-*.md`）

| # | 目标 | 完成标准 |
|---|---|---|
| **P0** | 设计（已完成） | 四屏稿 + 亮暗 token + 生成器脚本 |
| **P1** | 壳：工程骨架五层 + 四页假数据 UI ✅ **已完成**（build/test 通过，15 个测试；三档窗口尺寸实测通过）→ `phase1-shell.md` |
| **P2** | 引擎：接 HMCLCore（列已有版本 → 启动 → 抓日志流）；只做**探测并注册系统已有的 Java** | 用系统 Java 起一个真实的 1.20.1 实例 |
| **P3** | 下载与安装：原版版本清单、加载器安装（Forge/NeoForge/Fabric/Quilt）、下载源可切 | 空目录能装出一个可启动实例 |
| **P4** | 整合包：mrpack 导入 + `client-overrides/.launcher/instance.json` 可选配置 | 别人的 mrpack 导入即玩，Prism 也能正常装 |
| **P5** | 诊断：类名→jar 索引、元数据指纹、规则证据链、Mixin 冲突 | 拿真实 crash-report 给出结论 + 证据 + 一键处置 |
| **P6** | Java 运行时安装（**延后**：朋友的机器已经装过 Java）：Adoptium API + TUNA/华为云镜像安装器，接进 P2 的注册表 | 装好 JRE 21 并出现在运行时列表里 |
| **P7** | 打包分发：单 exe（自解压）+ AppImage + 签名清单自更新 | 两个平台各一个单文件，朋友双击即用 |

P2 虽然不做安装器，但**运行时注册表要先立起来**（探测到的和以后安装的都写进同一张表），
否则 P6 会变成"启动器装了一个 Java、引擎又去找另一个"。


## 未决问题

- 「打开实例文件夹」目前指**主实例**；是否改成"当前选中实例"或先跳实例列表？
- 右栏「获取内容」四项是否砍成三项（把"安装加载器"并进"下载游戏版本"）？
- `~/Downloads/Material-Design-Kit.penpot` 是坏文件（1.6M，非 zip），要重下才有 M3 组件库
- 动效参数：卡片 shape morph 24↔12dp、`spring(dampingRatio = 0.6f)`、页面 crossfade 200ms —— 写在规格里但没在真机验证过手感

## 环境备忘

- 项目在**工作站 Mayo-WS**（`ssh Mayo-WS`），dsh 也在那台机器（`command -v dsh` 可确认）
- 本会话曾跑在服务器（Mayo-Server）上，注意 `~/下载` vs `~/Downloads` 的差异
- 生成脚本用 python3 标准库，渲染 PNG 用 `rsvg-convert -w 1280 -h 800`
