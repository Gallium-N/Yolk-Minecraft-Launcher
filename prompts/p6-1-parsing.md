# P6.1 · 崩溃诊断：解析层 + 两个索引

> 任务书只读。commit：`feat(diag)` 模型与解析、`feat(diag)` 索引、`test(diag)` 夹具回归。
> 每个 commit 独立 `./gradlew build` 通过。**这一步不做规则、不做 UI**。

## 目标

把三种输入变成强类型模型，并建两个索引——后面所有规则和结论都从这里长出来。

```
crash-reports/crash-*.txt   → CrashReport
logs/latest.log, debug.log  → LogFile
hs_err_pid*.log             → NativeCrash
mods/*.jar                  → ClassIndex（类名 → jar）+ MixinIndex（mixin → jar）
```

## 一、模型（`domain/diagnosis/`，纯 Kotlin，禁 java.io）

```kotlin
data class CrashReport(
    val description: String,               // "Description: Ticking entity"
    val exceptionType: String?,            // java.lang.OutOfMemoryError
    val exceptionMessage: String?,
    val stackTrace: List<StackFrame>,      // at net.minecraft...(...:123)
    val system: SystemDetails,             // javaVersion, osName, osArch, memoryMax, gpu, javaVmArgs
    val mods: List<ModEntry>,              // modId, version, fileName（来自 FML/Mod List 节）
    val launchedVersion: String?,          // "Launched Version: 1.20.1-forge-47.2.0"
    val isClient: Boolean,                 // Minecraft Version: / Type: Client
    val raw: String,
    val sourcePath: String,
    val lines: Map<String, IntRange>,      // 分节名 → 行号区间，给证据链定位用
)

data class LogFile(
    val path: String,
    val lines: List<String>,
    val firstErrorLine: Int?,              // 第一条含 ERROR/Exception 的行
    val crashMarkers: List<Int>,           // "---- Minecraft Crash Report ----" / "A fatal error"
)

data class NativeCrash(                   // hs_err_pid*.log
    val signal: String?,                   // SIGSEGV (0xb)
    val problematicFrame: String?,         // C  [libGL.so+0x...] / J 1234 c2 some.Class.method
    val javaVersion: String?,
    val commandLine: String?,
    val currentThread: String?,
)
```

`stackTrace` 保留**行号**（原始报告里的行），证据链要能跳到 `crash-xxx.txt:87`。

## 二、解析器（`app/diagnosis/ReportParser.kt` 等，纯函数）

- `CrashReportParser.parse(text): CrashReport`
  - 分节按标题识别：`Description:`、`Stacktrace:`、`-- System Details --`、`-- Affected level --`、
    `FML Mod List` / `-- Mod List --`、`Launched Version:`
  - `System Details` 里逐行 `Key: Value` 解析（Java Version / Java VM Version / Memory / Operating System / GPU）
  - 异常类型与消息从 `Description:` 段的第一行 exception 行提取；若没有，从 Stacktrace 首帧上溯
  - **容忍脏数据**：缺节、乱序、CRLF、BOM、超长行（>8KB 截断并标记）都不许抛
- `LogParser.parse(lines)`：找 first-error、crash marker、最后 200 行窗口
- `HsErrParser.parse(text)`：signal、problematic frame、命令行
- 全部限制输入大小（报告 8MB、日志 64MB），超出只读头尾并标记 `truncated=true`

## 三、两个索引（`app/diagnosis/ModIndex.kt`）

- `ClassIndex`：扫 `<实例>/mods/*.jar`（含 `.disabled` 但要标记 `disabled=true`），读 zip 条目里
  以 `.class` 结尾的，建 `internalName("com/foo/Bar") → JarEntryRef(jarFile, modId?)`
  - 只读条目名，**不解压内容**；单 jar ≤ 64MB，总条目数上限 200k，防内存爆
  - modId 从 `fabric.mod.json` / `META-INF/mods.toml` / `mcmod.info` 里读；读不到就用文件名
- `MixinIndex`：读每个 jar 里 `*.mixins.json`（和 `*.refmap.json` 里的 mapping key），
  建 `mixin 类名 → jar`；同时记录 `target`（`"targets"`/`"client"` 数组里的类）
- 索引**惰性构建 + 落盘缓存**（`data/diag/index-<mtimeHash>.json`），mods 目录 mtime 变了就重建

## 四、夹具（这一步的关键资产）

仓库里现成 26 个崩溃报告 + 49 个日志：

```
vendor/hmcl/HMCLCore/src/test/resources/crash-report/*.txt
vendor/hmcl/HMCLCore/src/test/resources/logs/*.txt
```

把它们**复制**到 `composeApp/src/desktopTest/resources/diag/`（只复制用到的，≤20 个，别整目录搬），
文件名保持不变（它们本身按故障类型命名，P6.2 会用名字做期望值）。

至少覆盖这几类：`out_of_memory`、`graphics_driver`、`need_jdk11*`、`no_class_def_found_error*`、
`loader_exception_mod_crash*`、`mod_resolution0`、`processing_of_javaagent_failed`、
`file_already_exists`、`debug_crash`、`forge_error`、`loading_error_fabric`。

## 验收（缺一不算过）

1. `build`/`test` 绿；新增 ≥12 单测
2. **26 个崩溃报告全部解析成功**（不抛异常），贴一张表：文件名 → description / 异常类型 / mods 数 /
   launchedVersion，**至少 5 个手抄原文核对一致**
3. 49 个日志全部解析成功，`firstErrorLine` 抽查 5 个
4. 索引：对 `~/ymcl-test` 里任一实例建索引，贴 `类数 / jar 数 / 构建耗时 / 缓存命中耗时`；
   断言 `net/minecraft/client/main/Main` 这类原版类**不在** mod 索引里（我们只索引 mods）
5. 脏数据：造一个空文件、一个 12MB 超长报告、一个 CRLF+BOM 文件 → 不崩，各自给出 `truncated/empty` 标记
6. 性能：26 个报告顺序解析总耗时 < 300ms（贴数字）
7. `domain/` 里复查无 `java.io/nio`；解析器不自己 open 文件（IO 在 platform 层）
8. 报告：`文件:行`、`git log --oneline`、`git status --porcelain`

## 不要做

不做规则判定、不做 UI、不改现有诊断页占位、不动下载器、不引入新依赖（zip 读取用 JDK 自带）。
