package io.github.a13e300.ksuwebui

import com.topjohnwu.superuser.Shell

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    if (globalMnt) {
        builder.setFlags(Shell.FLAG_MOUNT_MASTER)
    }
    return builder.build()
}

// Same rule KernelSU/Magisk use for module ids; also blocks "..", "/" and friends.
private val MODULE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

fun isValidModuleId(id: String?): Boolean = id != null && MODULE_ID_REGEX.matches(id)

fun parseModuleProp(input: java.io.InputStream): Map<String, String> {
    val props = mutableMapOf<String, String>()
    input.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) props[parts[0].trim()] = parts[1].trim()
        }
    }
    return props
}

/** Quote a value so the shell treats it as a single literal word. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private val SHELL_ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun isValidEnvKey(key: String): Boolean = SHELL_ENV_KEY_REGEX.matches(key)

// A plain JS identifier path such as `window.cb_123` or `$exec_cb`.
private val JS_CALLBACK_REGEX = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")

fun isValidJsCallback(name: String): Boolean = JS_CALLBACK_REGEX.matches(name)
