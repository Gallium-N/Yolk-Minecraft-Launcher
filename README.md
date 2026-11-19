# YMCL · Yolk Minecraft Launcher

用 Kotlin + Compose Multiplatform 写的桌面 Minecraft 启动器，Material 3 界面，Windows / Linux 双平台。  
本地优先：没有后端、没有遥测、不上传数据。

[![License](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.0-gold)](https://github.com/Gallium-N/Yolk-Minecraft-Launcher/releases)

![启动台](media/screenshots/home.png)

## 能做什么

- **装游戏**：原版任意版本（含 1.6–1.12 的老版本资源映射），或 Fabric / Forge / Quilt / NeoForge
- **装 Mod**：Modrinth 搜索、版本过滤、依赖自动补齐
- **装整合包**：`.mrpack` 和 CurseForge zip，拖进窗口即可
- **管 Java**：自动发现本机 Java、按游戏版本选择，缺了就从国内镜像装一个 JRE
- **下载可靠**：默认 BMCLAPI 镜像（可关）、并发、断点续传、停滞检测、失败清单

🚧 崩溃诊断开发中。⏳ 正版登录、CurseForge 浏览、离线迁移包在计划里。

## 下载

到 [Releases](https://github.com/Gallium-N/Yolk-Minecraft-Launcher/releases) 下载：

| 平台 | 文件 |
|---|---|
| Windows | `YMCL-<版本>-win-x64.exe`（单文件，首启解压 1–3 秒）或 `-portable.zip`（便携） |
| Linux | `YMCL-<版本>-x86_64.AppImage`（`chmod +x` 后运行） |

不需要预装 Java。每个版本附 `SHA256SUMS`。

> Windows 会弹 SmartScreen（构建未签名）：**更多信息 → 仍要运行**。

## 上手

1. 打开 YMCL → **获取内容 → 安装游戏版本**，选一个版本
2. 需要 Mod 就选加载器，不需要就留「原版」
3. 点安装，等进度走完
4. 回 **实例** 页 → 启动

## 数据在哪

全部在 exe 旁边的 `.YMCL/` 里：

```
.YMCL/
├── game/versions/<实例>/   # 每个实例的 mods、config、saves、日志
├── java/                   # 启动器装的 Java
└── data/                   # 配置与缓存
```

搬家就整个拷走；卸载就删掉 exe 和 `.YMCL`（不写注册表）。
目录不可写时自动回退到 `%LOCALAPPDATA%\YMCL`。

## 常见问题

- **闪退 / `Failed to launch JVM`**：1.0.0 已修；仍有问题请下 `console-debug` 版本，在 cmd 里运行并把输出贴到 issue
- **下载慢**：设置里可关掉镜像走官方源；两边都慢通常是出口网络问题
- **游戏崩了**：看 `<实例>/crash-reports/` 和 `<实例>/logs/latest.log`（诊断功能做完后会直接给结论）

## 从源码构建

需要 JDK 21：

```bash
./gradlew run                  # 直接运行
./gradlew build                # 编译 + 测试
./gradlew createDistributable  # 生成带运行时的 app-image
```

## 许可证

GPL-3.0-or-later WITH AdditionRef-HMCL（引擎来自 [HMCL](https://github.com/HMCL-dev/HMCL)）。
见 [`LICENSE`](LICENSE) 与 [`vendor/README.md`](vendor/README.md)。

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
> 本项目不分发任何 Minecraft 游戏资源，游戏文件由启动器从官方源或其镜像下载。

## English

**YMCL** is a Minecraft launcher for Windows and Linux (Kotlin + Compose Multiplatform, Material 3).
Install vanilla or modded versions, import `.mrpack` / CurseForge modpacks, browse mods on Modrinth,
and let it manage Java. Portable (`.YMCL` next to the exe), no backend, no telemetry.
Build with JDK 21: `./gradlew run`. GPL-3.0-or-later. Not an official Minecraft product.
