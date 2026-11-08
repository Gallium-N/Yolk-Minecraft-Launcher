package dev.mayo.ymcl.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ModInstallException(message: String) : Exception(message)

private data class InstalledMod(
    val projectId: String,
    val versionId: String,
    val versionNumber: String,
    val filename: String,
    val sha1: String?,
)

data class ModInstallReport(
    val installed: List<String>,
    val skipped: List<String>,
    val failures: List<String>,
)

/**
 * Writes mod jars into `<gameRoot>/versions/<instanceId>/mods/`. Uses only the
 * [DownloadFileSystem] abstraction (no java.io in app layer); file bytes come
 * from the injected [fetch]. sha1 is verified after every write; a mismatch
 * deletes the file and redownloads exactly once, then goes to the failure list.
 */
class ModInstaller(
    private val fs: DownloadFileSystem,
    private val fetch: (String) -> ByteArray,
    private val log: (String) -> Unit = ::println,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sidecar(instanceId: String) = "versions/$instanceId/modrinth-mods.json"

    /** Installed project ids, from the sidecar (schemaVersion 1, unknown fields ignored). */
    fun installedProjectIds(instanceId: String): Set<String> {
        val path = sidecar(instanceId)
        if (!fs.exists(path)) return emptySet()
        return try {
            val o = json.parseToJsonElement(String(fs.readAll(path), Charsets.UTF_8)).jsonObject
            o["installed"]?.jsonArray?.toList()?.mapNotNull {
                runCatching { it.jsonObject["projectId"]?.jsonPrimitive?.content }.getOrNull()
            }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            log("MR sidecar unreadable ($path): ${e.message}")
            emptySet()
        }
    }

    private fun writeSidecar(instanceId: String, mods: List<InstalledMod>) {
        val text = buildJsonObject {
            put("schemaVersion", 1)
            put("installed", buildJsonArray {
                mods.forEach { m ->
                    add(buildJsonObject {
                        put("projectId", m.projectId)
                        put("versionId", m.versionId)
                        put("versionNumber", m.versionNumber)
                        put("filename", m.filename)
                        put("sha1", m.sha1 ?: "")
                    })
                }
            })
        }.toString()
        fs.writeNew(sidecar(instanceId), text.toByteArray(Charsets.UTF_8))
    }

    fun install(
        instanceId: String,
        planned: List<ModResolver.Planned>,
        instanceRunning: Boolean,
        onProgress: (String) -> Unit = {},
    ): ModInstallReport {
        if (instanceRunning) {
            throw ModInstallException(
                "实例 $instanceId 正在运行中，无法安装模组（进程会锁定 mods 文件）。请先停止游戏再安装。",
            )
        }

        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val records = mutableListOf<InstalledMod>()

        planned.forEach { p ->
            val filename = ModResolver.safeFilename(p.file.filename)
            if (filename == null) {
                val msg = "非法文件名被拒绝：${p.file.filename}"
                log("INSTALL ERROR: $msg")
                failures += msg
                return@forEach
            }
            val rel = "versions/$instanceId/mods/$filename"
            onProgress(filename)

            if (fs.exists(rel) && p.file.sha1?.let { fs.sha1Of(rel).equals(it, ignoreCase = true) } == true) {
                log("MR already installed $rel sha1=${p.file.sha1}")
                skipped += filename
                records += InstalledMod(
                    p.version.projectId, p.version.id, p.version.versionNumber, filename, p.file.sha1,
                )
                return@forEach
            }

            val failure = downloadVerified(rel, p.file.url, p.file.sha1)
            if (failure != null) {
                log("INSTALL ERROR: $failure")
                failures += failure
            } else {
                installed += filename
                log("MR installed $rel (${p.reason})")
                records += InstalledMod(
                    p.version.projectId, p.version.id, p.version.versionNumber, filename, p.file.sha1,
                )
            }
        }

        if (records.isNotEmpty()) {
            val merged = (readRecords(instanceId).associateBy { it.projectId } + records.associateBy { it.projectId }).values.toList()
            writeSidecar(instanceId, merged)
        }
        val report = ModInstallReport(installed, skipped, failures)
        log(
            "MR install summary: instance=$instanceId installed=${installed.size} " +
                "skipped=${skipped.size} failures=${failures.size}",
        )
        return report
    }

    /** Exactly one redownload on sha1 mismatch; never a silent skip. */
    private fun downloadVerified(rel: String, url: String, expectedSha1: String?): String? {
        val part = "$rel.part"
        var lastMismatch = ""
        repeat(2) { attempt ->
            fs.delete(part)
            val bytes = try {
                fetch(url)
            } catch (e: Exception) {
                fs.delete(part)
                return "$rel 下载失败：${e.javaClass.simpleName}: ${e.message}"
            }
            fs.append(part, bytes)
            val actual = fs.sha1Of(part)
            if (expectedSha1 == null || actual.equals(expectedSha1, ignoreCase = true)) {
                fs.delete(rel)
                fs.move(part, rel)
                return null
            }
            lastMismatch = "期望 sha1=$expectedSha1 实际=$actual"
            log("MR sha1 mismatch $rel ($lastMismatch) attempt=${attempt + 1}/2")
            fs.delete(part)
        }
        fs.delete(rel)
        return "$rel：重下一次后 sha1 仍不匹配（$lastMismatch）"
    }

    private fun readRecords(instanceId: String): List<InstalledMod> {
        val path = sidecar(instanceId)
        if (!fs.exists(path)) return emptyList()
        return try {
            json.parseToJsonElement(String(fs.readAll(path), Charsets.UTF_8)).jsonObject["installed"]
                ?.jsonArray?.toList()?.mapNotNull {
                    val o = runCatching { it.jsonObject }.getOrNull() ?: return@mapNotNull null
                    InstalledMod(
                        projectId = o["projectId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        versionId = o["versionId"]?.jsonPrimitive?.content ?: "",
                        versionNumber = o["versionNumber"]?.jsonPrimitive?.content ?: "",
                        filename = o["filename"]?.jsonPrimitive?.content ?: "",
                        sha1 = o["sha1"]?.jsonPrimitive?.content,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
