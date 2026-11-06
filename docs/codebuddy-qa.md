# CodeBuddy 交接疑问回应

日期：2026-11-06  
基线：`p2-engine`，HEAD `5d86b83`（本文件提交后 HEAD 会前进；见文末 tag 说明）  
构建：`./gradlew build` 通过；测试 **23 passed / 0 failed**  
交接 tag：`pre-hy4 = dd3767b`（保持不动）、`pre-hy4-qa = 5d86b83`

## Q1. CP936 的接入点与 P2 范围

**结论：P2 只做“解码纯函数 + 单测”，端到端 pump 替换留到 P7/Windows；这是有意的范围修正。**

原因和你判断一致：

- `DefaultLauncher.startMonitors()`（`vendor/hmcl/HMCLCore/.../DefaultLauncher.java:1019`）
  用 `command.encoding`（Linux 上来自 `-Dfile.encoding=UTF-8`）创建
  `StreamPump`（同文件 `:1021` / `:1026`），字节在 HMCLCore 内已经被解成 String；
- YMCL 侧通过 `HmclEngine` 的 `ProcessListener`（`HmclEngine.kt:150`）拿到的
  已经是 `onLog(String)`，原始字节拿不回来；
- 要做字节级端到端只有两条路：
  1. 自管 `ProcessBuilder`（会重写已经验证通过的 Forge 启动路径）；
  2. 改 `vendor/hmcl/` 源码（硬规则禁止）。

因此当前实现是：

- `expect/actual fun decodeLogBytes(bytes: ByteArray): String`
  （`Platform.kt:19`、`Platform.desktop.kt:184`）；
- UTF-8 严格解码优先，畸形时回退 GBK/CP936；BOM 处理保留；
- `readTextAuto` 改为复用该函数（`Platform.desktop.kt:201`），文件读取行为不变；
- 4 个单测：GBK 文件行、GBK 流、UTF-8 不误判、UTF-8 BOM
  （`TextFilesTest.kt:54/60/66/72`）；
- **没有**改 `DefaultLauncher`，**没有**改 vendor 源码，**没有**改已验证的 Forge 启动路径。

提交：`a5b9fe4 feat(log): add UTF-8/CP936 decoder helper`。

**报告口径：**
> P2 Linux 验收使用 UTF-8 日志；`decodeLogBytes` 与 GBK 单测已完成。
> 进程流端到端 CP936 回退不在 P2 范围，留到 P7/自管进程阶段。
> 这是范围修正，不是“原始任务书已全量完成”。

如果坚持原始 P2 的端到端要求，需要另开分支重写启动路径；不要混进当前 P2。

## Q2. 验收 5 的 Java 17 口径

**结论：原任务书这条写错了；报告按 Java 8 口径写，不伪造 Java 17 失败。**

- 1.20.1 本来就要求 Java 17+，用 Java 17 启动不会报错；
- 实际错误 Java 验收用真实 Temurin 8；
- 触发的是 `HmclEngine.requiredJavaMajor`（`HmclEngine.kt:357`）的预检：
  “Java 版本不匹配：1.20.1 需要 Java 17 或更高，当前是 Temurin 1.8.0_512。”
- 截图：`build/p2-wrong-java2.png`。

**报告口径：**
> 原验收文案“用 Java 17 启动 1.20.1”是笔误；1.20.1 要求 17+。
> 改用真实 Temurin 8 验证错误提示，未伪造 Java 17 失败。

## Q3. 15 个版本是否全部列出、无 skip

**结论：15 个全部列出，没有 skip。**

- `$HOME/Minecraft/versions` 下 15 个版本目录；
- `HmclEngine.listInstances`（`HmclEngine.kt:72`）逐目录解析；失败会打印
  `YMCL versions: skip ...`；
- 最终启动日志没有该行；
- 实例页顶部显示“15 个实例”，首屏 12 行，其余滚动；
- 证据截图：`build/p2-final-instances.png`。

报告里可以写“15 个全部加载；首屏 12 行 + 滚动”，不要只贴首屏截图就以为只有 12 个。

## Q4. pgrep 证据

**结论：原命令会自匹配，不能用作“空”的证据。**

```bash
# 错误示范：命令行自身包含 java.*minecraft，会被 pgrep 匹配
pgrep -af "java.*minecraft"

# 正确写法：方括号让模式不匹配自己
pgrep -af '[j]ava .*minecraft'
```

之前实际拿到空结果的是 bracket 变体，不是原命令。HANDOFF 已改成 bracket 版本；报告必须使用正确命令，或者用 `ps` 过滤掉 `grep` 自身。

## Q5. 诊断页左侧假数据

**结论：P5，不是 P2。**

- P2 只把右下日志面板接真实日志（`RawStackTracePanel`，`DiagnosticsPage.kt:254`）；
- 左侧结论/线索仍是 P1 假数据（`DiagnosticsContent`，`DiagnosticsPage.kt:44/83/180`）；
- HANDOFF 第 4 节第 4 条已写“P5 诊断（P2 交接不处理）”；
- 不要在 P2 报告里把它算成 P2 未完成项，只能列为“已移交 P5”。

## Q6. tag 策略

**结论：`pre-hy4` 保持不动；新交接点用 `pre-hy4-qa`；`p2-complete` 暂不打。**

```text
pre-hy4    = dd3767b9f1e9d71df2ab9a41d908eba68db52415   # 首版快照，不动
pre-hy4-qa = 5d86b831f12dde9d824198d72e34aefcab0e1e6d   # 问答澄清后
p2-complete：等 docs/p2-report.md 写完再打
```

`pre-hy4` 已经是给出去的首版交接点，不再移动；后续读者用 `pre-hy4-qa`。
本回应 doc 的提交在 `pre-hy4-qa` 之后，tag 不因此移动；如果要把本 doc 纳入交接点，
另打 `pre-hy4-qa2` 或按团队约定移动 `pre-hy4-qa`。

## 给 CodeBuddy 的短版回复

1. CP936：P2 只做 `decodeLogBytes` + 单测（已做，4 个测试，总 23）；不改 pump，端到端留 P7/Windows。报告写明这是范围修正。
2. Java 17：原任务书写错，1.20.1 要求 17+；错误 Java 用 Temurin 8，报告按 Java 8 写。
3. 版本：15 个全部列出，无 skip；首屏 12 行，其余滚动。
4. pgrep：原命令会自匹配；用 `pgrep -af '[j]ava .*minecraft'`，原“空”结果来自 bracket 变体。
5. 诊断页假数据：P5，P2 不处理；P2 只负责日志面板。
6. tag：`pre-hy4` 不动；`pre-hy4-qa` 是最新交接点；`p2-complete` 等 P2 报告写完再打。
