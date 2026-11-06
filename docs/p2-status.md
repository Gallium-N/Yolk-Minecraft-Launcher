# P2 checkpoint — 2026-11-05 23:36

Branch: `p2-engine`
HEAD before this note: `1477196` (plus tags `p2-java-ok`, `p2-launch-ok`)
Working tree: clean before this note.

## What is done

- HMCLCore is vendored under `vendor/hmcl/` from HMCL commit
  `b46a07cd45be326878498b22daa01a79ea3ea1bd` (scheme B). Scheme A
  (`includeBuild("HMCL")`) failed because HMCL uses Gradle 9.8 buildSrc against
  YMCL's Gradle 8.14; details in `docs/hmcl-api-notes.md`.
- Java runtime registry: `domain/JavaRuntime.kt`, `platform/JavaRegistry.kt`,
  detection in `platform/Platform.desktop.kt` (JAVA_HOME, PATH, system JVM
  roots, sdkman, Gradle JDKs). `data/java/runtimes.json` has 3 real system runtimes:
  Java 25 (Red Hat), Java 25 (Temurin), Java 21 (Temurin).
- `HmclEngine` lists all local versions by reading `versions/*/<id>.json`
  (currently 15) and launches through HMCLCore `DefaultLauncher`.
- UI wiring: real instance list, real loader versions (Forge 47.4.23 / Fabric
  0.19.3), Home running/stop state, launch error card, Diagnostics real log
  stream, Settings → Game directory + Java list, Settings → About HMCL notice.
- Real Forge 1.20.1 launch reached the main menu; normal "退出游戏" produced
  exit code 0 and the launcher returned to ready. Killing the process produced a
  readable "游戏进程异常退出（退出码 143）" card.
- Wrong-Java test: a real Temurin 8 JRE was temporarily placed under
  `build/jdks/` (now deleted) and detected. Selecting it for 1.20.1 showed:
  “Java 版本不匹配：1.20.1 需要 Java 17 或更高，当前是 Temurin 1.8.0_512。”
- Tests: 19 passing (15 P1 + 4 Java registry/detection tests).
  `./gradlew build` succeeds.

## Evidence files

Screenshots (build/ is gitignored):
- `build/p2-final-home.png` — real 15-version Home, Forge 47.4.23.
- `build/p2-final-instances.png` — real instance table.
- `build/p2-forge-menu.png` — Forge 1.20.1 main menu (game window).
- `build/p2-minecraft-menu.png` — vanilla 1.20.1 main menu.
- `build/p2-diagnostics.png` — live log panel while running.
- `build/p2-clean-exit.png` — clean exit, no error.
- `build/p2-wrong-java2.png` — readable wrong-Java error.
- `build/p2-settings-game.png` — game directory + Java list.

Logs:
- `~/.local/share/ymcl/data/logs/1_20_1-Forge-*.log`
- `~/.local/share/ymcl/data/logs/1_20_1-*.log`
- `~/.local/share/ymcl/data/java/runtimes.json`
- `~/.local/share/ymcl/data/launch-history.json`

## Open items / why stopped

2026-11-06 窗口复测：在 2446×1376 单窗口下启动器铺满窗口，未复现“只画左上角
1280×800”。只有把窗口压到很小（低于 1100×700）时会被 Hyprland 强制裁切，
按环境限制处理，**暂定关闭**。旧 P1 的 DBG 输出已从 `DesktopMain.kt`/
`YmclApp.kt` 删除。

Known gaps to pick up next time:
1. **P2 acceptance report** is not written. The prompt requires a final report
   with file:line list, `git log --oneline p1-baseline..HEAD`, tags, and rollback
   commands.
2. **Log stream encoding fallback** is not implemented: HMCLCore's
   `ManagedProcess`/`StreamPump` decodes with `OperatingSystem.NATIVE_CHARSET`
   (UTF-8 on this Linux box). The required UTF-8 → CP936 fallback for process
   streams is still open; file reading already has it via `readTextAuto`.
3. **Diagnostics page content** is still the P1 fake conclusion/clues; only the
   right-hand log panel is real (P5 owns the analysis rules).
4. Total play time is always 0; only last-launch time is persisted.
5. The Java probe test used a temporary JRE 8; it and its registry entry were
   removed. `runtimes.json` is back to 3 system runtimes.

## Resume

```bash
cd ~/src/ymcl
git switch p2-engine
git status --porcelain        # should be clean after this note commits
./gradlew build
./gradlew test
./gradlew :composeApp:createDistributable
composeApp/build/compose/binaries/main/app/YMCL/bin/YMCL
```

The wrapper that launches via Hyprland remains `build/run-hypr.sh`; it logs to
`build/hypr-ymcl.log`. The test-only Java 8 wrapper and JRE were removed.

## Rollback

```bash
git switch main && git branch -D p2-engine       # drop all of P2
git switch -c retry p1-baseline                  # restart from P1
git revert <hash>                                # revert one step
```
