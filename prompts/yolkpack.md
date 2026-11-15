# YolkPack：离线迁移包（导出 / 导入）

> 任务书只读。commit：`feat(yolkpack)` 导出、`feat(yolkpack)` 导入、`feat(ui)` 入口、`test`。
> 每个 commit 独立 `./gradlew build` 通过。

## 解决的问题

一个朋友网络好、另一个连 BMCLAPI 都不通（或限速到几 KB/s）。让网络好的人把**已下载的全部文件**
打成一个包，用 U 盘 / 网盘 / QQ 传给差的一方，对方导入后不下载、直接开玩。

前提：Minecraft 的 assets / libraries 是**内容寻址**的（sha1 即文件名），两边完全一致，
所以这不是"复制别人的游戏"，只是"复制同一批公开文件"，合法且可行。

## 包格式（.yolkpack = zip）

```
yolkpack.json                 # 清单
game/assets/objects/<hh>/<hash>
game/libraries/<maven 路径>
game/versions/<id>/<id>.json
game/versions/<id>/<id>.jar
instances/<instanceId>/        # mods/ config/ saves/ instance.json
README.txt                     # 说明：来源、版本、如何导入
```

`yolkpack.json`：

```json
{
  "formatVersion": 1,
  "kind": "full",                      // full | instance-only
  "createdBy": "YMCL 1.0.0 (abc1234)",
  "createdAt": "2026-11-15T...",
  "minecraft": "1.20.1",
  "loader": { "kind": "fabric", "version": "0.20.1" },
  "instance": { "id": "Fabulously-Optimized", "displayName": "Fabulously Optimized" },
  "files": [
    { "path": "game/assets/objects/ab/abcd...", "size": 12345, "sha1": "abcd..." }
  ],
  "totals": { "files": 3689, "bytes": 740830303 }
}
```

## 导出

入口：实例页三点菜单 →「导出迁移包（离线）」；设置页也放一个「导出全部资源」。

两种粒度：

- **instance-only**（小，几 MB–几百 MB）：只含 `instances/<id>/` + 该实例的版本 json。
  对方电脑上已有同版本资源时用这个
- **full**（大，~740MB）：再加上该版本**实际用到**的 assets 与 libraries（按 index 与版本 json 精确挑选，
  不要把整个共享根的其它版本文件塞进去）

实现要点：

- 用 `ZipOutputStream`，**对已压缩格式（.jar/.zip/.png）用 `STORED`**，省 CPU
- 进度：文件数 + 字节，可取消
- 边写边算每文件 sha1 与总 sha256，最后写 `yolkpack.json`（清单最后写入，保证包不完整时可识别）
- 输出到用户选的目录，命名 `YMCL-<mc>-<loader>-<date>.yolkpack`
- 超过 2GB 时按 500MB 分卷（`.yolkpack.part1`…），清单里记 `parts`

## 导入

入口：实例页「导入」按钮旁边加「导入迁移包」；也支持拖放 `.yolkpack`。

1. 读 `yolkpack.json`（缺清单 → "这不是有效的迁移包"）
2. 校验：格式版本、`kind`、分卷完整性
3. **预检报告**（导入前给用户看）：`将写入 3689 个文件 / 740MB`、
   `已存在且 sha1 相同：3200（跳过）`、`冲突：12（将覆盖本地，逐个列出）`、磁盘剩余是否够
4. 用户确认 → 解压写入 `gameRoot`：路径必须相对、拒绝 `..`/绝对路径/盘符（zip-slip），
   **不许写到 `gameRoot` 之外**
5. 每文件写完校验 sha1，不符 → 该文件进失败清单，其余继续
6. 实例注册：写 `instances/<id>/`，重名则 `-2` 递增，并在界面提示
7. 结束：`YolkPack imported: files=3689 bytes=740830303 skipped=3200 failed=0`
8. 导入完成后，实例页出现该实例，**不需要任何网络**

## 安全

- 迁移包里只有数据文件；**绝不执行**包内任何脚本/可执行文件（`.sh`/`.bat`/`.exe` 一律跳过并记录）
- 解压前校验总大小，超出声明总量 10% 直接中止（防 zip 炸弹）
- 拒绝符号链接条目

## 验收（缺一不算过）

1. `build`/`test` 绿；新增单测 ≥7（清单解析、zip-slip、可执行文件跳过、sha1 不符进失败清单、
   重名递增、分卷顺序、大小超限中止）
2. **导出真机**：对已装好的 1.20.1 + Fabric 实例导 full 包 → 贴包体积、文件数、
   与 `du -sh game/assets game/libraries` 对比（差额只允许是"未被该版本引用的文件"）
3. **离线导入真机**（核心）：新建空 `gameRoot`，**把 DownloadSource 指到不可达地址**（模拟没网）→
   导入刚导出的包 → 实例出现 → 不联网启动进主菜单。贴启动日志与截图
4. 预检报告截图（跳过/冲突/磁盘检查三项都要有数字）
5. 冲突覆盖：本地先放一个 sha1 不同的同名 mod，导入后按"覆盖"处理并记录
6. 失败清单：手工破坏包里一个文件的字节 → 导入后该文件进 failed 清单，其余成功，退出码非零
7. 报告：`文件:行`、`git log --oneline`、`git status --porcelain`、上述原样输出

## 不要做

做服务端/在线分享、加密、压缩率优化到极致、跨版本资源映射、写 `~/Minecraft`。
