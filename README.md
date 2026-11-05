# YMCL

Yolk Minecraft Launcher：Kotlin + Compose Multiplatform 的桌面 Minecraft 启动器。
当前为 P2：接 HMCLCore，支持本机版本列表、Java 运行时探测/注册、真实启动与日志流。

跑：`./gradlew run`；验：`./gradlew build && ./gradlew test`（JDK 21 / Gradle 8.14）。

## License

本程序基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的 HMCLCore，遵循
GPL-3.0-or-later WITH AdditionRef-HMCL。完整许可证见 [`LICENSE`](LICENSE)；
vendored 源码的来源、commit 与同步方式见 [`vendor/README.md`](vendor/README.md)。
YMCL 是独立名称，关于页保留 HMCL 版权声明。
