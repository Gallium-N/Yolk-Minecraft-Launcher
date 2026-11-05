# YMCL · Phase 1 提示词（交给 DeepSeek Harness）

> 用法：把下面 **「提示词正文」** 从标题开始整段复制给 dsh，不要带上这份说明和文末备注。
> 版本号已核实（Maven Central 稳定版）：Compose MP 1.12.2 / Kotlin 2.4.21 / serialization 1.12.0。
> 生成时间：2026-11-04 · 对应设计稿：`~/src/ymcl/design/mockups/`

---

# 任务：YMCL Phase 1 — 工程骨架 + 四页 UI 壳（不接引擎）

## 背景（你没有这段上下文，按本文为准）
YMCL = Yolk Minecraft Launcher，自用 + 几个朋友的 Minecraft 启动器，Windows/Linux 双平台，
无后端、不联网同步、不上传任何数据。界面严格 Material 3（Standard 骨架 + Expressive 圆角动效），
产品长项是"游戏崩了能说清为什么"（诊断功能留到后面）。
本轮只做两件事：(A) 一次性把工程骨架分层做对，(B) 用假数据跑出四个页面。
不接 HMCLCore、不引任何 GPL 依赖、不联网、不启动游戏。

工作目录：~/src/ymcl（已存在，内含设计资料，不要删改）
设计参考图（仅作视觉对照，尺寸数字以本文为准）：
  ~/src/ymcl/design/mockups/ymcl-{home,instances,diagnostics,settings}-dark.png
颜色 token（必须读它来生成主题，不要自己编颜色）：
  ~/src/ymcl/design/tokens/ymcl-dark.json
  ~/src/ymcl/design/tokens/ymcl-light.json
  格式为 W3C DTCG：{"color": {"primary": {"$value": "#E6C26C", ...}}}

## A. 工程骨架（这部分结构要一次做对，后面要长到跨平台）

- Gradle 单工程，一个模块 composeApp，target 只有 jvm("desktop")
- 版本固定（已核实存在，不要改动、不要"升级到 latest"）：
  Kotlin 2.4.21 / Compose Multiplatform 1.12.2 / kotlinx-serialization-json 1.12.0 / Gradle wrapper 8.14
- JDK：机器默认是 25，不要用。显式 jvmToolchain(21)，JDK 路径 /usr/lib/jvm/java-21-openjdk
- 包结构（严格按这五层，代码放对应包里）：
    dev.mayo.ymcl.ui        Compose 界面、主题、组件
    dev.mayo.ymcl.app       用例/状态（AppState、ViewModel 风格但不要引 DI 框架）
    dev.mayo.ymcl.domain    纯 Kotlin 模型与规则（Instance、LoaderKind、InstanceStatus…）
    dev.mayo.ymcl.platform  expect/actual：路径、进程、打开文件夹、系统深色模式、通知
    dev.mayo.ymcl.engine    GameEngine 接口 + FakeGameEngine（本轮只有假实现）
- 硬规则：domain 包内禁止 import java.io / java.nio / java.awt / java.lang.Process；
  所有平台相关能力只能从 platform 包拿。
- 双平台路径：内部一律用 '/' 拼路径，只在读写文件/启动进程的边界转成系统分隔符；
  提供 PathUtil.toNative(String)、PathUtil.join(..)、PathUtil.normalize(..)
- 实例名校验（防御 Windows/Linux 差异）：禁止 < > : " / \ | ? *、控制字符、
  结尾空格与点、Windows 保留名（CON PRN AUX NUL COM1-9 LPT1-9，大小写不敏感）；
  另外限制长度，拼接后总路径超过 240 字符要给出可读的错误
- 文本读取：readTextAuto(file) = UTF-8 优先，遇到 MalformedInputException 回退 CP936(GBK)；
  写出 JSON 一律 UTF-8 无 BOM、LF 换行
- 配置文件分两层（这是跨 OS 的关键，务必分开）：
    instance.json      可移植：id、显示名、mcVersion、loader(kind+version)、mods 列表、
                       封面、totalPlayTimeMs、lastLaunchedAt、schemaVersion=1
    machine.local.json 绑本机：javaPath、xmxMb、窗口尺寸、GPU 偏好
  用 kotlinx.serialization；读的时候忽略未知字段（ignoreUnknownKeys = true），
  读到 schemaVersion > 1 直接报错而不是静默继续
- 本轮 FakeGameEngine 返回 5 个虚构实例（名字和设计稿一致：地下酒吧 / 空岛生存 /
  红石测试场 / Create: 机械时代 / 原版 26.4 快照），数据全部走内存，不落盘

## B. 四页 UI

窗口默认 1280×800，最小 1100×700；左侧 NavigationRail 固定 88 宽，内容区左 24 留白。
页标题 titleLarge(22) 在左上，右侧同基线放一小段 meta 文本（labelMedium 12）。

四页：启动台 / 实例 / 诊断 / 设置（图标依次 PlayArrow / Folder / Warning / Settings，
只用 Icons.Default 里已有的，先确认存在；不要引 material-icons-extended）

【启动台】
- Hero 卡 1144×240 圆角 24，对角线性渐变 primaryContainer → tertiaryContainer
  - 左上「主实例」labelSmall；标题 36/700；下方四个统计块（标签 11 + 值 15）：
    游戏版本 / 模组加载器 / 总游玩 / 上次启动，第 i 块 x = 卡内 32 + i*210
  - 右侧按钮组：齿轮圆钮 48dp（半透明描边次级样式）+ 启动圆钮 72dp（primary 填充）
- 下方两列：左 620 / 间距 24 / 右 500
  - 左「最近实例」+ 右侧 hint「按上次启动排序」；两张卡各 620×144 圆角 24，间距 16
    卡内：名称 16/600、副信息 12（"26.1 · Fabric 0.16.9"）、右上「上次启动 …」11 右对齐、
    状态 chip 96×26、右上角更多按钮（MoreVert）
  - 左列底部「所有实例」条 620×56 圆角 16 surfaceContainerHigh，右侧「共 5 个 ›」
  - 右「获取内容」面板 500×372 圆角 24 surfaceContainerLow，四行各 84：
    下载游戏版本 / 打开实例文件夹 / 浏览模组与整合包 / 导入本地整合包
    行内：40dp 图标圆底 surfaceContainerHigh + 标题 14/600 + 副 11 + 右侧 chevron；
    行间 1px outlineVariant(40%)；只有第一行图标用 primary 色

【实例】
- 工具条 y=88 高 44：搜索框 360（surfaceContainerHigh，全圆角，放大镜 + 占位文案）、
  右侧「最近启动 ▾」132 描边按钮、「新建实例」148 primary 按钮
- 表格容器 1144×504 圆角 24 surfaceContainerLow；表头 11px；行高 92，从 y=196 开始
- 六列（相对内容区 x）：实例 88 / 版本·加载器 300 / 总游玩 560 / 上次启动 720 / 状态 880 / 操作 1040
- 每行：48×48 圆角 14 primaryContainer 首字块、名称 15/600 + 「N 个模组」11、
  版本 13 + 加载器 11、总游玩 13、上次启动 13、状态 chip 92×26
  （运行中=primaryContainer / 就绪=secondaryContainer / 可更新=tertiaryContainer）、
  操作列 = 36dp 播放圆钮 + MoreVert
- 表底一行 11px 汇总（共 5 个实例 · 2 个可更新）

【诊断】
- 左 700 / 右 420
- 结论卡 700×196：「可能原因」labelSmall → 两行人话结论 22/700 →
  「置信度 92%」chip 104×26 + 一行 12（证据 3 条 · 崩溃时间 · 加载器版本）
- 动作按钮 y=300 高 44：禁用 flywheel-1.0.4（primary 236 宽）/
  降级 embeddium → 0.6.1（surfaceContainerHigh 252）/ 复制诊断报告（描边 180）
- 其他线索卡 700×268：标题 14 + 三条（圆点 + 标题 13/600 + 说明 12 + 右侧等宽来源 10），每条 68 高
- 右侧原始堆栈面板 420×608：标题 14 + 文件名 chip 210×24（等宽 10）；
  堆栈文本等宽 10.5、行距 22、从 y=168 起；第 6~8 行整行用 secondaryContainer(55%) 高亮；
  分隔线后是「自动定位」三行等宽文本（类名 → jar）
- 底部 y=652 高 44：保存为 Markdown / 重新分析 + 右侧一行 11px 说明
- 所有文案照设计稿写死即可（这是假数据演示）

【设置】
- 左子导航 240 宽，5 项各 44 高圆角 12（外观/游戏/启动/下载/关于），选中项 secondaryContainer
- 右 880 卡片列（间距 16，从 y=88 起）：
  主题（高 140，标题+副文案+分段控件 320×40：跟随系统/浅色/深色）
  从壁纸自动取色（高 160，标题+副文案+右上开关 44×24 + 5 个 28dp 颜色圆点 + 一行说明）
  减少动效（高 120，标题+副+开关）
  界面密度（高 130，标题+副+分段 220×40：舒适/紧凑）
- 后四组（游戏/启动/下载/关于）只做占位：显示组名 + 一行「待实现」

## 主题与动效
- 读上面两个 token JSON，生成 Theme.kt：亮/暗两套 ColorScheme，49 个角色逐一映射
  （含 surfaceContainer*、surfaceDim/Bright、inverse*、*Fixed 等 Compose 1.12 已支持的字段）
- 默认跟随系统深浅色；支持 JVM 参数 -Dymcl.theme=dark|light 覆盖（方便你在 Linux 上验证）
- 圆角：卡片 24 / 内嵌条 16 / 列表项 12 / 按钮全圆；间距只用 4/8/16/24/32
- 动效（别省）：卡片 hover/选中圆角 24↔12 的 shape morph，用 animateDpAsState +
  spring(dampingRatio = 0.6f)；页面切换 Crossfade 200ms
- 不做边框描边，层次全靠 tonal surface（surface / surfaceContainerLow / High）
- 字号：displaySmall 36 / titleLarge 22 / titleMedium 16 / bodyMedium 14 / labelMedium 12 / labelSmall 11

## 单元测试（必须有，本轮不用 UI 测试）
写 JUnit 测试覆盖，至少 8 个用例：
1) 实例名含非法字符被拒 2) Windows 保留名 CON 被拒（大小写不敏感）
3) 合法中文实例名通过 4) 路径拼接在 Windows 下产出反斜杠、内部表示仍是 /
5) 超长路径给出可读错误 6) UTF-8 文件正常读取
7) GBK 编码文件能回退读出中文 8) instance.json 里出现未知字段仍能解析
9) schemaVersion=2 时明确报错

## 验收（你必须自己真跑过，不许假设）
1. ./gradlew build 输出 BUILD SUCCESSFUL（贴结尾 5 行）
2. ./gradlew test 全部通过（贴用例数与结果）
3. timeout 30 ./gradlew run 能起窗口，stdout 出现 "YMCL window ready"，无异常堆栈
   （超时退出是正常的）
4. 报告里给出 文件:行：导航切换、主题生成、Hero 卡、四个页面入口、PathUtil、编码工具
5. 只允许写 ~/src/ymcl 内的文件（README.md 三行以内：是什么/怎么跑/当前阶段）

## 不要做
- 不接网络、不登录、不下载、不启动 Minecraft
- 不引 HMCLCore 或任何 GPL 依赖（Phase 2 才接）
- 不引 DI 框架、不引第三方 UI 库、不引 material-icons-extended
- 不写除 README 之外的长文档
- 不动 ~/src 下的其他目录

## 卡住时
选最简可行方案继续推进，在 README 里留一行 "TODO: …"，不要停下来反复确认。
如果 Compose 1.12.2 与 Kotlin 2.4.21 报兼容性错误，把 Kotlin 降到 2.4.20 再试，
仍不行就把完整报错原样输出，不要自己猜着换一堆版本。

---

## 给阿酱的备注（这段不要粘给 dsh）

- **验收第 3 条会往你屏幕上弹窗口**，这是故意的：机器上没装 Xvfb，headless 截图不划算，
  你亲眼看一下圆角和动效最直接。
- 让 dsh 从 token JSON 生成 `Theme.kt`，而不是手抄 49 个色值——防止它抄错或自己编颜色。
- 它报版本兼容 / Compose 桌面渲染的错，把**原文**贴回来。这类地方它容易自己硬试一堆版本，越试越乱。
- 跑完把汇报（Build 结尾、测试用例数、文件:行 清单）贴回来，我对照验收标准挑问题，
  再出 Phase 2（接 HMCLCore）的提示词。
- 后续提示词也放这个目录：`prompts/phase2-engine.md`、`phase3-download.md`、`phase4-mrpack.md`、`phase5-diagnostics.md`。
