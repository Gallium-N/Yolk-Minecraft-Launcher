# YMCL · P2 提示词：接 HMCLCore（列版本 → 启动 → 日志流）

> 整段复制给 dsh。前置：P1 已完成（`build`/`test` 通过，窗口尺寸问题已修）。
> 本阶段只做"跑起来"，不做下载、不做整合包、不做诊断规则。

---

# 任务：P2 — 把 HMCLCore 接进 YMCL，跑通"列已有版本 → 启动 → 日志流"

## 背景与已确认事实
- YMCL：Kotlin + Compose Multiplatform 桌面启动器，目录 `~/src/ymcl`，五层结构已就位
  （ui / app / domain / platform / engine），engine 层目前只有 `GameEngine` 接口 + FakeGameEngine
- 本机已有游戏：`~/.minecraft`（含 1.20.1、1.20.1-Forge、1.20.1-Fabric、1.21.1、26.1.2 等版本）
- 本机 Java：`/usr/lib/jvm/java-21-temurin-jdk`（系统里还有 17/25）；`JAVA_HOME` 可能为空
- HMCL 是 **GPLv3 + §7 附加条款**（分发修改版必须改名区分、不得移除其版权声明）；
  YMCL 已是独立名字，只在"关于"里保留 HMCL 版权行即可
- 网络：Maven Central 可用；GitHub 从这台机器可访问（若被墙，先问用户开代理，不要自己重试十次）

## 第 -1 步：Git 纪律（先做，这一阶段全程有效）
用户会随时反悔或换方向，所以每一步都必须可回退。规则如下：

1. **起点先固定**
   - `git -C ~/src/ymcl rev-parse --git-dir` 失败 → 这是新仓库：`git init`，把当前 P1 成果
     作为第一个 commit（`.gitignore` 必须已经排除 `build/`、`.gradle/`、`.gradle-user-home/`、`*.log`）
   - 然后 `git tag p1-baseline`，这是"退回 P1"的锚点
   - 确认 `git status --porcelain` 干净；有未提交改动就先 commit 或 stash，**不要带着脏树开工**
2. **开分支**：`git switch -c p2-engine`（不要直接在 main 上改）
3. **提交粒度**：每个"能构建通过"的里程碑一个 commit，不要一次性大提交。最低要求这 5 个：
   - `docs: record HMCLCore API recon`（第 0 步的侦察笔记，放 `docs/hmcl-api-notes.md`）
   - `build: wire HMCLCore via composite build`（失败就改成 `build: vendor HMCLCore module`）
   - `feat(java): runtime registry + detection`
   - `feat(engine): HmclEngine launch with log stream`
   - `feat(ui): wire real versions and launch state`
   每个 commit 的信息用英文，格式 `<type>(<scope>): <what>`；**每个 commit 都必须能 `./gradlew build` 通过**
4. **绝对禁止**：`git push --force`、`git reset --hard`（在 main 或已推送分支上）、删分支、改写历史、
   `git checkout -- .` 覆盖用户未提交的改动。需要撤销自己的改动时用 `git revert <hash>`，
   或在新分支上重做。
5. **阶段末打 tag**：Java 注册表通了打 `p2-java-ok`；成功启动真实实例后打 `p2-launch-ok`
6. **报告里必须附**：
   - `git log --oneline p1-baseline..HEAD`（证明提交粒度）
   - `git tag --list` 输出
   - 一段"如何回退"说明，至少包含这三条可直接执行的命令：
     ```
     git switch main && git branch -D p2-engine        # 丢掉整个 P2
     git switch -c retry p1-baseline                    # 从 P1 状态重开一条
     git revert <hash>                                  # 只撤某一步
     ```
7. 如果方案 A（composite build）失败并改走方案 B，**保留方案 A 那次提交的历史**，
   用新 commit 表达"回退到 vendor"，不要 rebase 掉失败的尝试——用户要能看到为什么转弯
8. `vendor/hmcl/` 里的 HMCL 源码副本要一起提交（GPLv3 允许，且能保证构建可复现），
   并在 `vendor/README.md` 写清来源 commit

## 第 0 步：侦察（先做，只读，不写代码）
1. `git clone --depth 1 https://github.com/HMCL-dev/HMCL.git ~/src/HMCL`（或按用户指示的代理方式），
   记下 `git -C ~/src/HMCL rev-parse --short HEAD` 的 commit
2. 读这三处，然后在报告里列出你实际要用的 API（类名 + 方法签名，附文件路径）：
   - `HMCLCore/src/main/java/org/jackhuang/hmcl/game/DefaultGameRepository.java`
   - `HMCLCore/src/main/java/org/jackhuang/hmcl/java/JavaManager.java`（及 `JavaRuntime` 相关类）
   - `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java`（或当前等价入口）
3. 报告里同时给出：`HMCLCore/build.gradle.kts` 的依赖清单里，有哪些是你必须一并提供的
   **不要在这一步就开始写代码**，先把 API 摸清楚贴出来。

## 第 1 步：把 HMCLCore 引进构建（两种方案，按顺序尝试）
**方案 A（首选）：Gradle 复合构建**
```kotlin
// settings.gradle.kts
includeBuild("../HMCL") {
    dependencySubstitution {
        substitute(module("org.jackhuang.hmcl:HMCLCore")).using(project(":HMCLCore"))
    }
}
```
若 HMCL 的根构建脚本在配置期报错（JavaFX 插件、toolchain、它自己的子项目要求），
**不要改 HMCL 的源码或构建文件**，改用方案 B。

**方案 B（回退）：vendor 模块**
把 `~/src/HMCL/HMCLCore` 与 `minecraft/libraries/HMCLTransformerDiscoveryService`、
`minecraft/libraries/HMCLMultiMCBootstrap` 复制到 `~/src/ymcl/vendor/hmcl/`，作为本仓库的
Gradle 子项目（`include(":hmcl-core")`，`projectDir` 指向 vendor 目录），依赖版本照抄
`~/src/HMCL/gradle/libs.versions.toml`，写进 `gradle/libs.versions.toml` 自己维护。
在 `vendor/README.md` 里写死：来源仓库、commit、许可证、同步方式。

**禁止方案 C**：直接把 jar 丢进 libs/ 当二进制依赖（没法读源码、无法合规追溯）。

两种方案都要做的事：
- 项目许可证改为 GPL-3.0：加 `LICENSE`（GPLv3 全文）、README 加一段"本程序基于 HMCLCore，遵循 GPLv3"
- 设置页"关于"分组里加一行 HMCL 版权声明（现在可以只放静态文本）

## 第 2 步：Java 运行时注册表（这是 P2 的一半工作量，别跳过）
在 `domain` 层定义模型，`platform` 层做探测（禁止在 domain 里 import java.io）：

```kotlin
data class JavaRuntime(
    val id: String,          // "temurin-21.0.13+9-x64-linux" 之类，稳定唯一
    val vendor: String,      // temurin / system / user-added
    val major: Int,          // 8 / 17 / 21 / 25
    val version: String,     // 完整版本串
    val arch: String,        // x64 / aarch64
    val os: String,          // windows / linux
    val path: String,        // 相对 .YMCL 的路径，或是绝对路径 + source=system
    val source: String,      // "detected" | "installed"
    val installedAt: String?
)
```
- 持久化：`<appRoot>/data/java/runtimes.json`，结构 `{ "schemaVersion": 1, "runtimes": [...] }`，
  读取用 `ignoreUnknownKeys`，`schemaVersion > 1` 时报错而不是继续
- 探测来源（Linux 侧至少覆盖前三个，Windows 侧按注释实现，P2 只验收 Linux）：
  `JAVA_HOME`、`PATH` 里的 `java`、`/usr/lib/jvm/*`、`~/.sdkman/candidates/java/*`、
  `~/.gradle/jdks/*`；Windows 侧注释里留 `%ProgramFiles%\Eclipse Adoptium\*` 与注册表项
- 每个候选项跑一次 `<java> -XshowSettings:properties -version`，解析
  `java.version` / `java.vendor` / `os.arch`；超时 10s，失败的候选忽略并记日志
- **把探测结果注册进 HMCLCore 的 JavaManager**，让引擎的版本选择能看到同一批运行时——
  这是本阶段最容易出的 bug："启动器探测到 A，引擎却去用 B"
- 单测：用 `@TempDir` 伪造 `JAVA_HOME` 与一个假的 `bin/java` 脚本（输出固定 properties），
  验证解析；再测 `runtimes.json` 的读写、未知字段容忍、schemaVersion=2 报错

## 第 3 步：GameEngine 实现（HMCLEngine）
`engine` 层新增 `HmclEngine`，实现已有的 `GameEngine`；**ui 层不许 import 任何
`org.jackhuang.hmcl.*` 类型**，全部在 engine 层转换成本项目的 domain 模型：

```kotlin
interface GameEngine {
    suspend fun detectJava(): List<JavaRuntime>
    suspend fun listVersions(gameDir: String): List<GameVersion>
    suspend fun launch(req: LaunchRequest): LaunchHandle
}
data class GameVersion(val id: String, val mcVersion: String, val loader: LoaderKind?, val lastModified: Long)
data class LaunchRequest(val instanceId: String, val gameDir: String, val javaId: String, val xmxMb: Int, val extraJvmArgs: List<String>)
class LaunchHandle(val process: Process, val logs: kotlinx.coroutines.flow.Flow<String>, val exitCode: kotlinx.coroutines.flow.Flow<Int>)
```
- `listVersions`：只读扫描 `<gameDir>/versions/*/<id>.json`，不要联网、不要"补全"缺失文件
- `launch`：用 HMCLCore 组装 classpath / 参数（复用它的 Forge/Fabric 处理），但
  **进程由我们自己起**（`ProcessBuilder`），以便接管 stdout/stderr
- 日志流：`InputStream` 逐行读，**UTF-8 优先，`MalformedInputException` 回退 CP936**；
  同时落盘到 `<appRoot>/data/logs/<instanceId>-<timestamp>.log`（UTF-8）
- 进程清理：退出/取消时 `process.toHandle().descendants()` 全部 `destroy()`，
  3 秒后仍活着的 `destroyForcibly()`（Windows 的 Job Object 留到 P7，P2 先按此实现并写 TODO）
- 失败可读化：退出码非 0 时，把 stderr 最后 30 行连同"可能原因"的粗分类
  （Java 版本不符 / 缺 libraries / 模组崩溃）显示在 UI 上，不要只弹一个白框

## 第 4 步：UI 接线（改现有四页，不新加页面）
- 实例页：数据源换成真实 `listVersions`，实例卡显示 id / MC 版本 / 加载器 / 模组数（数 `mods/*.jar`）
- 启动台 Hero：显示"最近启动"的那个实例（真实数据），播放按钮 → `launch()`
- 启动中：Hero 的播放按钮变成"运行中 + 停止"，实例状态 chip 用 `primaryContainer`
- 诊断页：在日志面板位置实时显示本次启动的日志流（滚到底部、等宽字体、UTF-8）
- 设置 →「游戏」分组（原来是占位）：显示"游戏目录"（默认 `~/.minecraft`，可改）+
  Java 运行时列表（只读展示 P2 探测到的，安装器留到 P6）

## 验收（每一条都要有真实输出，缺一不算过）
1. `./gradlew build` 通过；`./gradlew test` 通过（P1 的 15 个测试 + 新增 Java 探测/编码测试）
2. 实例页列出 `~/.minecraft/versions` 下 **≥8** 个真实版本，贴出列表截图或日志
3. `runtimes.json` 里至少注册 1 个真实 Java，贴出文件内容
4. **启动一个真实实例并进入主菜单**（建议 `1.20.1-Forge` 或 `1.20.1`）：
   - 贴日志流前 20 行（含 `Setting user:` / `LWJGL Version` 之类）
   - 游戏窗口出现的截图（`grim`）
   - 退出游戏后：`pgrep -af "java.*minecraft"` 输出为空，日志文件已落盘
5. 故意用错 Java（把 `javaId` 指向 Java 17 去启动 1.20.1）→ UI 上出现可读错误，
   不是白屏也不是静默失败，贴出错误文案
6. 报告里给出改动文件:行 清单 + 你对 HMCLCore 哪几个类做了适配
7. Git 证据齐全：`git log --oneline p1-baseline..HEAD`、`git tag --list`、以及回退命令三条
   （缺这些视为未完成，即使功能跑通）

## 约束
- 不改 HMCL 仓库里的任何文件（除方案 B 的复制；复制后也不要修改其源码）
- UI 层零 HMCL 类型泄漏；`grep -rn "org.jackhuang" composeApp/src/commonMain` 必须为空
- 不联网下载游戏文件（P3 才做）；`listVersions` 只读本地
- 不引新 UI 库、不引 DI 框架
- 所有新增持久化文件带 `schemaVersion`

## 卡住时的处理
- HMCL 构建配置失败 → 走方案 B，别硬扛方案 A
- HMCLCore 的 API 和本文写的不一致 → 以源码为准，把你实际用的签名写进报告
- 启动失败先看 stderr 再改代码；不确定就贴原始输出并停下，不要连续猜
