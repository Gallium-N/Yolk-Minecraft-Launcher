# P3 第 3 步报告 — C 组失败清单留档

> 2026-11-07。本文件是「网络烂时行为正确」的物证归档：C 组全量冷装（无代理、无预热）
> 的 14 个失败文件完整清单。留证日志：`build/p3-c-cold.log`（7615 行）。

## C 组结果（允许不通过，只记录基线）

```
SUMMARY files=3689 bytes=725129351 elapsedMs=422700 failures=14 skipped=25 redownloaded=86
BUILD FAILED in 7m 3s
```

| 指标 | 值 |
|---|---|
| 计划 / 成功 / 失败文件 | 3689 / 3675 / **14** |
| 真实下载 / cached | 3653 / 24 |
| 平均速率（前 138s 窗口） | `progress files=3650 bytes=690480024 elapsedMs=138068` → **26.4 文件/秒、4.77 MB/s** |
| 平均速率（全程，含重试） | 3689 / 422.7s → **8.7 文件/秒、1.64 MB/s** |
| STALL@30s / TIMEOUT@300s / degraded | **0 / 1 / 4** |
| 失败文件数 | **14**（均列入 `INSTALL ERROR`，进程非零退出） |
| `.part` 残留 | 0 |

14 个失败的形态一致：源返回 **985–989 字节的错误页**（期望 4303–15414697 字节），
sha1/size 双校验失败 → 删 `.part` 重下一次 → 仍失败 → 报出文件名与期望/实际 hash 与
size → 计入失败清单 → `exitProcess(1)`。没有静默跳过、没有外层重试掩盖、没有半截文件。

## 14 个失败文件完整清单

「期望 sha1」即 `5.json` 中该对象的 hash（内容寻址）；「实际 sha1」为重下后第二次得到的
错误页内容 sha1（第一次的值见日志候选错误串）。

| # | 相对路径 | 期望 sha1 (前 12) | 实际 sha1 (前 12) | 期望 size | 实际 size |
|---:|---|---|---|---:|---:|
| 1 | `assets/objects/b3/b389fbb5495a7275ac89f2485bf545da0baca471` | `b389fbb5495a…` | `595c5456fb42…` | 19370 | 988 |
| 2 | `assets/objects/b0/b0cf74066c092372c4aa838d394e5a6fefe9c219` | `b0cf74066c09…` | `830f19f1a8b6…` | 26643 | 986 |
| 3 | `assets/objects/85/85ff3e2aabe317a1aa8637c27188b6940fd759dd` | `85ff3e2aabe3…` | `1baeb69e4cdb…` | 19607 | 989 |
| 4 | `assets/objects/ab/ab5c5769a19fa7f4b0fe3c0fd01763812272a857` | `ab5c5769a19f…` | `931f41cbe519…` | 18154 | 989 |
| 5 | `assets/objects/7b/7bc0bbd5a259c1d2aacff9d3e07543a9d1caaaca` | `7bc0bbd5a259…` | `e1e9cdb55720…` | 33807 | 988 |
| 6 | `assets/objects/5d/5d19075995133236a29995f9cb1929294e3df59e` | `5d1907599513…` | `93bcd73ef8d2…` | 16412 | 987 |
| 7 | `assets/objects/6a/6ac381cb9b69baf96b909e59d8c9886579746e4f` | `6ac381cb9b69…` | `72f143a93a0c…` | 7143 | 985 |
| 8 | `assets/objects/28/281f889e8eadcb21e2bb664d4f337192fab3a139` | `281f889e8ead…` | `07a2261ea956…` | 4303 | 988 |
| 9 | `assets/objects/90/90a81a62b2da5ff26ee2e06e93704e5ef96bec2a` | `90a81a62b2da…` | `4cd5929b8830…` | 22826 | 988 |
| 10 | `assets/objects/e0/e0f2f371bf22eb5f5c669b246468ccae3301912f` | `e0f2f371bf22…` | `419197c9fa0c…` | 65431 | 989 |
| 11 | `assets/objects/d5/d5b2d34e0c07a6c09c1adb17a15e50a626a88bc1` | `d5b2d34e0c07…` | `113a187b7d34…` | 8632 | 986 |
| 12 | `assets/objects/8e/8ed3945df8b9264c6451c153357475482d9f2066` | `8ed3945df8b9…` | `20e5239b3e83…` | 38114 | 985 |
| 13 | `assets/objects/66/6615ab7f668384cf98b0cb034ce8e4c52eaf86b2` | `6615ab7f6683…` | `42722f23baca…` | 15414697 | 988 |
| 14 | `assets/objects/9f/9f2c4bab5ed55f1714fffa223985c81babc8f6c0` | `9f2c4bab5ed5…` | `ab4a4e975c30…` | 5813 | 985 |

样例原始日志行（第 1 条）：

```
INSTALL ERROR: assets/objects/b3/b389fbb5495a7275ac89f2485bf545da0baca471：重下一次后校验仍失败
（期望 sha1=b389fbb5495a7275ac89f2485bf545da0baca471 / 实际=595c5456fb422599e5e6fc9c33778d7d4913d410，
期望 size=19370 / 实际=988）；候选错误：...#1 sha1 期望=... 实际=... size 期望=19370 实际=988;
...#2 sha1 期望=... 实际=595c5456... size 期望=19370 实际=988
```

## 归档说明

- 这是**上游返回错误页**导致的失败，不是代码缺陷：下载器正确识别了 sha1/size 不匹配，
  按口径只重下一次，然后如实报错并计入失败清单，进程以非零码退出。
- 留待网络好/代理时复跑这 14 个对象，即可拿到干净的「国内无代理基线」对照。
- A 组（结构证明）与 B 组（预热真跑 + 主菜单截图）均通过，见最终交付报告。
