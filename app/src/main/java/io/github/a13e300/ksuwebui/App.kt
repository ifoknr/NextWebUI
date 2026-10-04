package io.github.a13e300.ksuwebui

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
        val executor by lazy { Executors.newCachedThreadPool() }
        val handler = Handler(Looper.getMainLooper())
        const val PREF_DYNAMIC_COLORS = "dynamic_colors"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
        // The calm palette is the default; wallpaper colors are opt-in.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        Prefs.applyThemeMode(prefs)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> prefs.getBoolean(PREF_DYNAMIC_COLORS, false) }
                .build()
        )
    }
}
