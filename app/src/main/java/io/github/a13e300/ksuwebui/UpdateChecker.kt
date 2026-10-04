package io.github.a13e300.ksuwebui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Reads each module's `updateJson` (Magisk/KernelSU format) and remembers newer versions. */
object UpdateChecker {
    private val results = ConcurrentHashMap<String, UpdateInfo>()
    private val checked = ConcurrentHashMap<String, Long>()
    private const val TTL = 30 * 60 * 1000L

    fun cached(id: String): UpdateInfo? = results[id]

    /** Blocking; call from a background thread. Returns true when anything changed. */
    fun check(modules: List<Module>, force: Boolean): Boolean {
        var changed = false
        val now = System.currentTimeMillis()
        for (m in modules) {
            val url = m.updateJson ?: continue
            val key = "${m.id}@${m.versionCode}"
            if (!force && now - (checked[key] ?: 0L) < TTL) continue
            checked[key] = now
            val info = runCatching { fetch(url) }.getOrNull() ?: continue
            val newer = info.versionCode > m.versionCode
            val old = results[m.id]
            if (newer) {
                if (old != info) changed = true
                results[m.id] = info
            } else if (old != null) {
                results.remove(m.id)
                changed = true
            }
        }
        return changed
    }

    private fun fetch(url: String): UpdateInfo? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.length > 256 * 1024) return null
            val json = JSONObject(text)
            return UpdateInfo(
                version = json.optString("version"),
                versionCode = json.optLong("versionCode", -1L),
                zipUrl = json.optString("zipUrl").takeIf { it.isHttpUrl() },
                changelog = json.optString("changelog").takeIf { it.isHttpUrl() },
            )
        } finally {
            conn.disconnect()
        }
    }
}
