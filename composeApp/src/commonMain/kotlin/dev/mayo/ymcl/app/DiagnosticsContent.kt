package dev.mayo.ymcl.app

/** Static fake content for the diagnostics page (phase 1: no rule engine yet). */
data class DiagnosticConclusion(
    val causeLine1: String,
    val causeLine2: String,
    val confidence: Int,
    val evidenceCount: Int,
    val crashTime: String,
    val loader: String,
    val lastCrashMeta: String,
)

data class DiagnosticClue(
    val title: String,
    val detail: String,
    val source: String,
)

data class StackTraceContent(
    val lines: List<String>,
    val highlightedLineIndexes: Set<Int>,
    val fileName: String,
    val located: List<String>,
)

object DiagnosticsContent {
    val conclusion = DiagnosticConclusion(
        causeLine1 = "Flywheel 与 Embeddium 抢同一个",
        causeLine2 = "Mixin 注入点 · MixinBlockRenderer",
        confidence = 92,
        evidenceCount = 3,
        crashTime = "昨天 21:12",
        loader = "Forge 47.4.16",
        lastCrashMeta = "最近一次崩溃 · 昨天 21:12",
    )

    val clues = listOf(
        DiagnosticClue(
            title = "内存逼近上限",
            detail = "Xmx 8G / 物理 16G，崩溃前 GC 停顿 4.2s",
            source = "latest.log",
        ),
        DiagnosticClue(
            title = "OptiFine 残留在 mods 目录",
            detail = "未启用，但与 Embeddium 共存有已知风险",
            source = "mods/",
        ),
        DiagnosticClue(
            title = "同类冲突出现过一次",
            detail = "3 天前同一注入点报错，禁用后恢复",
            source = "历史诊断",
        ),
    )

    val stackTrace = StackTraceContent(
        lines = listOf(
            "java.lang.RuntimeException: Mixin transformation of",
            "net.minecraft.client.renderer.LevelRenderer failed",
            "    at MixinProcessor.applyMixins(:392)",
            "    at MixinTransformer.transformClass(:218)",
            "",
            "Caused by: MixinApplyError:",
            "Mixin apply failed flywheel.mixins.json:",
            "MixinBlockRenderer -> LevelRenderer",
            "    at flywheel.impl.mixin.MixinBlockRenderer(:41)",
            "    at embeddium.mixin.render.LevelRendererMixin(:88)",
            "... 24 more",
        ),
        highlightedLineIndexes = setOf(5, 6, 7),
        fileName = "crash-report_21_12.txt",
        located = listOf(
            "3 个类名命中了 mods 索引",
            "LevelRenderer → embeddium-0.6.0.jar",
            "MixinBlockRenderer → flywheel-1.0.4.jar",
            "MixinProcessor → (Mixin 框架)",
        ),
    )
}
