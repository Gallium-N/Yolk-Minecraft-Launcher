package dev.mayo.ymcl.app

import dev.mayo.ymcl.platform.PathUtil
import dev.mayo.ymcl.platform.readTextAuto
import dev.mayo.ymcl.platform.writeTextUtf8Lf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LauncherSettingsFile(
    val schemaVersion: Int = 1,
    val preferBmclapi: Boolean = true,
)

/** Persists launcher-wide preferences at `<appRoot>/data/launcher.json` (UTF-8, no BOM). */
class LauncherSettings(
    private val appRoot: String,
    private val log: (String) -> Unit = ::println,
) {
    val file: String = PathUtil.join(appRoot, "data", "launcher.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Reads the settings. Missing / malformed files and unsupported schemaVersion
     * fall back to defaults; the file is never silently rewritten by a read.
     */
    fun load(): LauncherSettingsFile {
        val content = try {
            readTextAuto(file)
        } catch (e: Exception) {
            log("YMCL settings: 读取 $file 失败，回落默认（${e.message}）")
            return LauncherSettingsFile()
        }
        val parsed = try {
            json.decodeFromString(LauncherSettingsFile.serializer(), content)
        } catch (e: Exception) {
            log("YMCL settings: $file JSON 损坏，回落默认（${e.message}）")
            return LauncherSettingsFile()
        }
        if (parsed.schemaVersion > 1) {
            log("YMCL settings: 不支持的 schemaVersion ${parsed.schemaVersion}，回落默认")
            return LauncherSettingsFile()
        }
        return parsed
    }

    fun save(settings: LauncherSettingsFile) {
        writeTextUtf8Lf(file, json.encodeToString(LauncherSettingsFile.serializer(), settings) + "\n")
    }

    /** Startup entry point: the persisted switch decides BMCLAPI vs official. */
    fun source(): DownloadSource = defaultDownloadSource(load().preferBmclapi)
}

// TODO(settings-ui): 下载分组里加「优先使用国内镜像（BMCLAPI）」开关，写入
// LauncherSettings.save()；P3 第 3 步不做 UI。
