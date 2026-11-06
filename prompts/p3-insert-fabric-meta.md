# P3 插入更正（运行中插入，2026-11-06）

> 给正在跑 P3 的 agent：这不是新阶段，是对 `prompts/phase3-install.md` 的两处更正。
> 先同步任务书，再对照你已做的工作决定返工范围。

## 1. 同步任务书（不要 rebase）

```bash
git status --porcelain          # 有未提交改动先 commit 或 stash，别丢
git merge --ff-only p3-start    # p3-start 已前移到 bbb088e
```

若 `--ff-only` 失败，说明你已在自己分支上提交过：改用 `git cherry-pick bbb088e`，
**不要** rebase、不要 reset。

## 2. Fabric 安装：改走 meta API，不要自己拼 profile

旧口径（作废）：从 `maven.fabricmc.net` 拿 loader 元数据再自己生成 profile json。

新口径：Fabric 有给启动器准备的 meta API，直接给成品 profile：

```
https://meta.fabricmc.net/v2/versions/loader/<mcVersion>                       → loader 版本列表
https://meta.fabricmc.net/v2/versions/loader/<mcVersion>/<loaderVer>/profile/json → 成品 profile json
```

- profile json 已含 `inheritsFrom`、`mainClass`、`libraries`（asm / mixin / intermediary 都在里面）
- 你要做的只有：取它 → 把里面 library URL 按 `DownloadSource` 重写 → 下载 → 当版本 json 落盘
- `maven.fabricmc.net` 只剩两个用途：meta 不可用时兜底枚举版本
  （`net/fabricmc/fabric-loader/maven-metadata.xml`），以及下载 profile 里引用的 jar
- **禁止自己拼 profile**：漏一个依赖就是启动时 `NoClassDefFoundError`，而且很难查
- Quilt 同构（`meta.quiltmc.org`），P3 允许只做接口不实现

## 3. 侦察报告补三项

```
https://meta.fabricmc.net/v2/versions/loader/1.20.1
https://meta.fabricmc.net/v2/versions/loader/1.20.1/<loaderVer>/profile/json
https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml
```
给出状态码 + 耗时；profile json 那一项贴出 `mainClass`、`libraries` 条数、前两条 library 的 name。

## 4. 返工范围怎么定

- 若你**还没写 Fabric 安装代码**：直接按新口径做，把上面如实写进报告
- 若你**已经写了自拼 profile 的逻辑**：保留"枚举 loader 版本"那部分（可复用），
  删掉自拼逻辑，改成取 profile json；在报告里单独写一节「因任务书更正而返工的改动」，
  列出被删的文件:行 —— 不要悄悄重写掉，也不要回滚掉其它已完成的部分

## 5. 其余不变

仍然先交侦察报告、不要顺手开工第 1 步；BMCLAPI 的已知事实（只有 `/versions/` 与
`x-bmclapi-hash`，`/maven`、`/assets` 要自己拼）照原文执行。
