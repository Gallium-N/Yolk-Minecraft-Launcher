# P8 手动验收清单（Win10 干净 VM）

> 由用户（阿酱）在干净 Win10 虚拟机上手动执行。本机无 Win10 VM，故不在此环境验证。
> 前提：VM 里 `where java` 输出为空（无 JDK），且未安装 YMCL。从 GitHub Release 下载
> `YMCL-<ver>-win-x64.exe`（SFX 单文件）与 `SHA256SUMS`。

## 0. 准备

1. 在宿主机对下载的 exe 算 sha256，与 `SHA256SUMS` 比对：
   ```powershell
   Get-FileHash .\YMCL-<ver>-win-x64.exe -Algorithm SHA256
   ```
   期望：与 SHA256SUMS 中该行一致（大小写不敏感）。
2. 在 VM 里确认无 Java：`where java`。期望：输出“找不到”/空。
3. 把 exe 放到任意可写目录（如桌面）。**不要**放 `C:\Program Files`。

## 1. 双击 SFX → 首启解压 → 窗口起来

- 双击 `YMCL-<ver>-win-x64.exe`。
- 期望：出现 7-Zip 自解压进度窗口，约 1–3 秒后消失（首启才会解压）。
- 解压目标：`%LOCALAPPDATA%\YMCL\<ver>`（资源管理器地址栏输入 `%LOCALAPPDATA%\YMCL` 可见 `<ver>` 目录，内有 `YMCL.exe`、`app\`、`runtime\`）。
- 期望：解压完成后 YMCL 主窗口自动弹出（不需要用户再点一次）。
- 记录：首启从双击到窗口出现的大致秒数。

## 2. exe 文件属性图标

- 在桌面右键 exe → 属性 → 看“常规”标签左上角的大图标。
- 期望：深色立方体 + 亮黄色 Y（Y 立方体），与 `design/logo/ymcl-mark-dark.ico` 一致。
- 若显示默认 Windows 程序图标 = 失败，记录。

## 3. 任务栏 / Alt-Tab 图标（专门验运行时图标）

- YMCL 窗口打开时，看任务栏里 YMCL 的图标。
- 按 `Alt+Tab` 切换到 YMCL，看切换器里的图标。
- 期望：两者都是亮色 Y 立方体（`ymcl-mark-light-256.png`，即 `composeResources/drawable/ymcl.png`），不是通用窗口图标。
- 若任务栏/Alt-Tab 是空白或默认图标 = 运行时图标失败，记录。

## 4. 建 1.20.1 实例 → 进主菜单

1. 在 YMCL 中点“安装游戏”→ 版本选 `1.20.1` → 加载器选“原版”→ 完成创建。
2. 回到实例列表，选中刚建的 1.20.1，点启动。
3. 期望：游戏窗口出现，进入 Minecraft 主菜单（Singleplayer / Multiplayer / Options）。
4. 若报 Java 版本错误或闪退，记录错误文本（VM 无 JDK，YMCL 应自动使用自带运行时或提示缺 Java）。

## 5. 二次启动是否复用解压

- 关闭 YMCL 与游戏。
- 再次双击同一个 exe。
- 期望：**不再**出现自解压窗口（或明显更快，<1 秒直接开窗）。
- 佐证：`%LOCALAPPDATA%\YMCL\<ver>` 目录已存在且含完整文件。
- 记录：二次启动是否出现解压窗口、大致秒数。

## 6. 结果反馈

把以下回填到 issue / 对话：

- [ ] `where java` 为空
- [ ] 首启解压耗时：___ 秒；窗口正常弹出
- [ ] exe 属性图标 = Y 立方体（是 / 否）
- [ ] 任务栏图标 = Y 立方体（是 / 否）
- [ ] Alt-Tab 图标 = Y 立方体（是 / 否）
- [ ] 1.20.1 进主菜单（是 / 否；错误文本：___）
- [ ] 二次启动复用解压（是 / 否；耗时：___ 秒）

截图要求：窗口级截图（grim / Win+Shift+S 选窗口），不要整屏兜底；至少留“任务栏图标”和“1.20.1 主菜单”两张窗口级截图。
