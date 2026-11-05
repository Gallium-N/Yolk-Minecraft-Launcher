package dev.mayo.ymcl.app

import dev.mayo.ymcl.domain.Instance
import dev.mayo.ymcl.domain.InstanceStatus
import dev.mayo.ymcl.domain.Loader
import dev.mayo.ymcl.domain.LoaderKind
import dev.mayo.ymcl.domain.ModEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import dev.mayo.ymcl.platform.readTextAuto
import dev.mayo.ymcl.platform.writeTextUtf8Lf

class UnsupportedSchemaException(message: String) : Exception(message)

class ConfigFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Portable half of an instance. Everything here can travel to another machine/OS.
 * Machine settings (javaPath, xmxMb, window size, GPU preference) live in
 * [MachineLocalConfig] and must never leak into this file.
 */
@Serializable
data class InstanceConfig(
    val schemaVersion: Int = 1,
    val id: String,
    val displayName: String,
    val mcVersion: String,
    val loader: LoaderConfig = LoaderConfig(),
    val mods: List<ModConfig> = emptyList(),
    val cover: String? = null,
    val totalPlayTimeMs: Long = 0L,
    val lastLaunchedAt: Long? = null,
)

@Serializable
data class LoaderConfig(
    val kind: String = "vanilla",
    val version: String = "",
)

@Serializable
data class ModConfig(
    val fileName: String,
    val name: String = "",
    val version: String = "",
)

@Serializable
data class MachineLocalConfig(
    val schemaVersion: Int = 1,
    val javaPath: String = "",
    val xmxMb: Int = 4096,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val gpuPreference: String = "auto",
)

/**
 * JSON boundary for the two config layers. Unknown fields are ignored on purpose
 * (forward compatibility), but a newer schemaVersion is a hard error: silently
 * continuing could rewrite a file written by a future version.
 */
object ConfigCodec {
    const val SUPPORTED_SCHEMA_VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun decodeInstance(text: String): Instance {
        val config = try {
            json.decodeFromString<InstanceConfig>(text)
        } catch (e: SerializationException) {
            throw ConfigFormatException("instance.json 解析失败：${e.message}", e)
        }
        if (config.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "instance.json 的 schemaVersion=${config.schemaVersion}，" +
                    "高于当前支持的 $SUPPORTED_SCHEMA_VERSION；请升级 YMCL 或使用旧版配置",
            )
        }
        val kind = LoaderKind.fromId(config.loader.kind)
            ?: throw ConfigFormatException("instance.json 含未知加载器：${config.loader.kind}")
        return Instance(
            id = config.id,
            displayName = config.displayName,
            mcVersion = config.mcVersion,
            loader = Loader(kind, config.loader.version),
            mods = config.mods.map { ModEntry(it.fileName, it.name, it.version) },
            cover = config.cover,
            totalPlayTimeMs = config.totalPlayTimeMs,
            lastLaunchedAt = config.lastLaunchedAt,
            status = InstanceStatus.READY,
        )
    }

    fun encodeInstance(instance: Instance): String = json.encodeToString(
        InstanceConfig.serializer(),
        InstanceConfig(
            id = instance.id,
            displayName = instance.displayName,
            mcVersion = instance.mcVersion,
            loader = LoaderConfig(instance.loader.kind.id, instance.loader.version),
            mods = instance.mods.map { ModConfig(it.fileName, it.name, it.version) },
            cover = instance.cover,
            totalPlayTimeMs = instance.totalPlayTimeMs,
            lastLaunchedAt = instance.lastLaunchedAt,
        ),
    )

    fun readInstance(path: String): Instance = decodeInstance(readTextAuto(path))

    fun writeInstance(path: String, instance: Instance) {
        writeTextUtf8Lf(path, encodeInstance(instance))
    }

    fun decodeMachineLocal(text: String): MachineLocalConfig {
        val config = try {
            json.decodeFromString<MachineLocalConfig>(text)
        } catch (e: SerializationException) {
            throw ConfigFormatException("machine.local.json 解析失败：${e.message}", e)
        }
        if (config.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "machine.local.json 的 schemaVersion=${config.schemaVersion}，" +
                    "高于当前支持的 $SUPPORTED_SCHEMA_VERSION",
            )
        }
        return config
    }

    fun encodeMachineLocal(config: MachineLocalConfig): String =
        json.encodeToString(MachineLocalConfig.serializer(), config)
}
