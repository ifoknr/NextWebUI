package io.github.a13e300.ksuwebui

import com.topjohnwu.superuser.nio.FileSystemManager

data class Module(
    val id: String,
    val name: String,
    val desc: String,
    val author: String,
    val version: String,
    val versionCode: Long,
    val dir: String,
    val hasWebUI: Boolean,
    val hasAction: Boolean,
    val disabled: Boolean,
    /** Absolute path, http(s) URL, or null when the module has no banner. */
    val banner: String?,
    val updateJson: String?,
    val support: String?,
    val donate: String?,
    val pinned: Boolean = false,
    val update: UpdateInfo? = null,
)

data class UpdateInfo(
    val version: String,
    val versionCode: Long,
    val zipUrl: String?,
    val changelog: String?,
)

object ModuleLoader {
    const val MODULES_DIR = "/data/adb/modules"
    private val BANNER_FILES = listOf("banner.webp", "banner.png", "banner.jpg", "banner.jpeg")

    fun load(fs: FileSystemManager, showDisabled: Boolean, webUIOnly: Boolean, pinned: Set<String>): List<Module> {
        val result = mutableListOf<Module>()
        fs.getFile(MODULES_DIR).listFiles()?.forEach { f ->
            if (!f.isDirectory || !isValidModuleId(f.name)) return@forEach
            val propFile = fs.getFile(f, "module.prop")
            if (!propFile.exists()) return@forEach
            if (fs.getFile(f, "remove").exists()) return@forEach
            val disabled = fs.getFile(f, "disable").exists()
            if (disabled && !showDisabled) return@forEach
            val hasWebUI = fs.getFile(f, "webroot").isDirectory
            if (webUIOnly && !hasWebUI) return@forEach

            val props = runCatching { parseModuleProp(propFile.newInputStream()) }.getOrDefault(emptyMap())
            val id = f.name
            val dir = f.absolutePath
            result.add(
                Module(
                    id = id,
                    name = props["name"]?.takeIf { it.isNotBlank() } ?: id,
                    desc = props["description"].orEmpty(),
                    author = props["author"].orEmpty(),
                    version = props["version"].orEmpty(),
                    versionCode = props["versionCode"]?.toLongOrNull() ?: 0L,
                    dir = dir,
                    hasWebUI = hasWebUI,
                    hasAction = fs.getFile(f, "action.sh").isFile,
                    disabled = disabled,
                    banner = resolveBanner(fs, dir, props["banner"]),
                    updateJson = props["updateJson"]?.takeIf { it.isHttpUrl() },
                    support = props["support"]?.takeIf { it.isHttpUrl() },
                    donate = props["donate"]?.takeIf { it.isHttpUrl() },
                    pinned = id in pinned,
                )
            )
        }
        return result
    }

    private fun resolveBanner(fs: FileSystemManager, dir: String, declared: String?): String? {
        val value = declared?.trim().orEmpty()
        if (value.isHttpUrl()) return value
        if (value.isNotEmpty()) {
            val path = if (value.startsWith("/")) value else "$dir/$value"
            val file = fs.getFile(path)
            // Only allow images that live inside the module folder.
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            if (canonical != null && canonical.startsWith("$dir/") && file.isFile) return canonical
        }
        for (base in listOf(dir, "$dir/webroot")) {
            for (name in BANNER_FILES) {
                val file = fs.getFile("$base/$name")
                if (file.isFile) return file.absolutePath
            }
        }
        return null
    }
}

fun String.isHttpUrl(): Boolean = startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)
