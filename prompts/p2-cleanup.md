# YMCL · P2 收尾提示词（交接前清理，不动功能）

> 整段复制给 dsh 或 CodeBuddy。前置：P2 已完成，`pre-hy4` / `pre-hy4-qa` / `p2-complete` 均已存在。
> 本轮只改配置与文档，不写功能代码。

---

# 任务：P2 收尾清理（只改配置与文档，不动功能）

## 背景
P2 已通过独立复核（23 测试、15 个真实版本、Forge 1.20.1 真实启动、分层纪律、进程清理）。
但交接给下一个 coding agent（Hy4）之前，有 5 处会使后来者踩坑的问题要修。
**本轮禁止改动任何功能代码**，只改配置、文档与一处注释。

## 1. gradle.properties 去掉本机绝对路径（最重要）
提交的 `gradle.properties` 里现在有：
```
org.gradle.java.home=/usr/lib/jvm/java-21-temurin-jdk
org.gradle.java.installations.paths=/usr/lib/jvm/java-21-temurin-jdk
```
这会让仓库在 Windows 或任何 JDK 路径不同的机器上直接构建失败。
- 从**提交的文件**里删掉这两行
- 本机需要的话，写进用户级 `~/.gradle/gradle.properties`（不入库）
- HANDOFF §5 改成："本机 JDK 21 通过 `JAVA_HOME` 或 `~/.gradle/gradle.properties` 指定；
  仓库内不写任何绝对路径"
- 验证：删掉后 `./gradlew build` 仍然通过（`JAVA_HOME` 已指向 Temurin 21 的情况下）

## 2. HANDOFF.md 修正两处过时内容
- §4 第 1 条「补 P2 最终验收报告」：已完成，删除该条（`docs/p2-report.md` 存在，commit `57f8d6e`）
- §8「`p2-complete` 暂不打」：改成实际状态（该 tag 已打在 `57f8d6e` 上）
- 通读一遍 HANDOFF，凡是"待做/暂不打/未完成"的描述，逐条与 git 现状核对

## 3. vendor/README.md 措辞改准确
现在的说法是「源码副本未改动」，但 `vendor/hmcl/HMCLCore/build.gradle.kts` 是 YMCL
自己重写的 70 行（上游版本依赖 buildSrc 里的 `UpdateClientSha1` 等类，无法直接用）。
必须写清：
- **Java 源码（src/main/java 等）未做任何修改**
- **构建脚本 `build.gradle.kts` 是 YMCL 为脱离上游 buildSrc 重写的**，并说明与原文件的差异
- 上游 commit、同步方式（重新拷贝哪些路径、保留哪些自有文件）
这条在 GPL 合规上是实质性区别，不能含糊。

## 4. 补一条遗留项到 docs/p2-report.md
在「遗留与移交」里新增：启动参数中的 `${clientid}` / `${auth_xuid}` 宏未被替换
（真实日志可见：`--clientId, ${clientid}, --xuid, ${auth_xuid}`），1.20.1 + Forge 不读这两个参数
所以当前无影响，但 **P3/P4 接正版登录/Realms 前必须处理**。附上日志证据行。

## 5. 交接锚点：修完打新 tag
`pre-hy4` 与 `pre-hy4-qa` 是**修复之前**的快照，如果下一个 agent 从它们起手会继承上面第 1 条。
- 全部修完、`./gradlew build` 通过后，提交一个 commit：`chore: P2 handoff cleanup`
- 打 tag `hy4-start`，指向这个 commit
- 在 HANDOFF §8 里加一行：`hy4-start` 是**给下一个 agent 的推荐起手点**

## Git 纪律
- 单独一条分支：`git switch -c p2-cleanup`
- 允许拆成 2 个 commit（配置+文档 / 报告补充），每个 commit 必须能 `./gradlew build` 通过
- **不要重打或移动已有 tag**（`pre-hy4`、`pre-hy4-qa`、`p2-complete` 保持不动）
- 不改写历史、不 force push

## 验收（缺一不算过）
1. `./gradlew build` 通过；`./gradlew test` 仍为 23 个测试全过
2. `grep -rn "/usr/lib/jvm\|/home/decafmayo" --include=*.properties --include=*.kts --include=*.kt .`
   结果为空（`build/` 目录除外）
3. HANDOFF 的「下一步」只剩 **P3 下载与安装** 与 **P5 诊断** 两条，没有已完成项残留
4. `git tag --list` 含 `hy4-start`，且 `git rev-parse --short hy4-start` 的 commit
   与 `p2-complete` 不同
5. 报告里给出：改动文件:行、`git log --oneline p2-complete..HEAD`、`git tag --list`

## 不要做
- 不改任何功能代码、不改 UI、不动 `prompts/` 下的原始任务书
- 不动 `vendor/hmcl/` 里的 Java 源码
- 不新增依赖
