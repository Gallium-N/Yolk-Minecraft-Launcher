package dev.mayo.ymcl.platform

import dev.mayo.ymcl.domain.JavaRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JavaRegistryFile(
    val schemaVersion: Int = 1,
    val runtimes: List<JavaRuntime> = emptyList(),
)

/**
 * Persists the union of system-detected and future user-installed Java runtimes at
 * `<appRoot>/data/java/runtimes.json`.
 *
 * This is the single list handed to both the UI and the engine, so the engine can
 * never pick a different Java than the one the settings page shows.
 */
class JavaRegistry(
    private val appRoot: String = appDataDirectory(),
) {
    val file: String = PathUtil.join(appRoot, "data", "java", "runtimes.json")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun loadOrDetect(detected: List<JavaRuntime>): List<JavaRuntime> {
        val existing = load()
        val merged = LinkedHashMap<String, JavaRuntime>()
        existing.forEach { merged[it.path] = it }
        detected.forEach { merged[it.path] = it }
        val runtimes = merged.values.sortedWith(
            compareByDescending<JavaRuntime> { it.major }.thenBy { it.path },
        )
        save(runtimes)
        return runtimes
    }

    fun load(): List<JavaRuntime> {
        val content = try {
            readTextAuto(file)
        } catch (_: Exception) {
            return emptyList()
        }
        val parsed = try {
            json.decodeFromString(JavaRegistryFile.serializer(), content)
        } catch (e: Exception) {
            println("YMCL java registry: keeping no entries, malformed $file: ${e.message}")
            return emptyList()
        }
        check(parsed.schemaVersion <= 1) {
            "Unsupported Java registry schemaVersion ${parsed.schemaVersion} in $file"
        }
        return parsed.runtimes
    }

    fun save(runtimes: List<JavaRuntime>) {
        val text = json.encodeToString(
            JavaRegistryFile.serializer(),
            JavaRegistryFile(schemaVersion = 1, runtimes = runtimes),
        )
        writeTextUtf8Lf(file, text + "\n")
    }
}
