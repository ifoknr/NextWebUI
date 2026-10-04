package io.github.a13e300.ksuwebui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.a13e300.ksuwebui.databinding.ItemSettingBinding

object Prefs {
    const val SHOW_DISABLED = "show_disabled"
    const val WEBUI_ONLY = "webui_only"
    const val CHECK_UPDATES = "check_updates"
    const val MONET_IN_WEBUI = "enable_monet"
    const val DEBUGGING = "enable_web_debugging"
    const val THEME_MODE = "theme_mode"

    fun applyThemeMode(prefs: SharedPreferences) {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getInt(THEME_MODE, 0)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}

/** Builds the settings screen out of plain views; no preference library needed. */
class SettingsPage(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val prefs: SharedPreferences,
    private val onModulesFilterChanged: () -> Unit,
) {
    private val dp = activity.resources.displayMetrics.density

    fun build() {
        section(R.string.settings_appearance) {
            val modes = arrayOf(
                activity.getString(R.string.theme_system),
                activity.getString(R.string.theme_light),
                activity.getString(R.string.theme_dark),
            )
            valueRow(it, R.drawable.ic_dark, R.string.theme, R.string.theme_summary, { modes[prefs.getInt(Prefs.THEME_MODE, 0)] }) { refresh ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.theme)
                    .setSingleChoiceItems(modes, prefs.getInt(Prefs.THEME_MODE, 0)) { d, which ->
                        prefs.edit { putInt(Prefs.THEME_MODE, which) }
                        d.dismiss()
                        refresh()
                        Prefs.applyThemeMode(prefs)
                    }
                    .show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                toggleRow(it, R.drawable.ic_palette, R.string.dynamic_colors, R.string.dynamic_colors_summary, App.PREF_DYNAMIC_COLORS, false) {
                    activity.recreate()
                }
            }
        }
        section(R.string.nav_modules) {
            toggleRow(it, R.drawable.ic_eye, R.string.show_disabled, R.string.show_disabled_summary, Prefs.SHOW_DISABLED, false) { onModulesFilterChanged() }
            toggleRow(it, R.drawable.ic_filter, R.string.webui_only, R.string.webui_only_summary, Prefs.WEBUI_ONLY, false) { onModulesFilterChanged() }
            toggleRow(it, R.drawable.ic_update, R.string.check_updates, R.string.check_updates_summary, Prefs.CHECK_UPDATES, true) { onModulesFilterChanged() }
        }
        section(R.string.settings_webui) {
            toggleRow(it, R.drawable.ic_palette, R.string.enable_monet_color, R.string.monet_summary, Prefs.MONET_IN_WEBUI, true) {}
            toggleRow(it, R.drawable.ic_bug, R.string.enable_webview_debugging, R.string.debugging_summary, Prefs.DEBUGGING, BuildConfig.DEBUG) {}
        }
        section(R.string.settings_general) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                valueRow(it, R.drawable.ic_lang, R.string.language, R.string.language_summary, null) {
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${activity.packageName}".toUri())
                        )
                    }
                }
            }
            valueRow(it, R.drawable.ic_bug, R.string.export_logs, R.string.export_logs_summary, null) { exportLogs() }
            valueRow(it, R.drawable.ic_info, R.string.about, R.string.about_summary, { BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" }) {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri()))
                }
            }
        }
    }

    private fun exportLogs() {
        Toast.makeText(activity, R.string.exporting_logs, Toast.LENGTH_SHORT).show()
        App.executor.submit {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "NextWebUI-log-$stamp.txt"
            val path = "/sdcard/Download/$name"
            val header = "NextWebUI ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}"
            val ok = runCatching {
                withNewRootShell {
                    newJob().add(
                        "{ echo ${shellQuote(header)}; logcat -d -v threadtime -t 5000; } > ${shellQuote(path)} 2>&1"
                    ).exec().isSuccess
                }
            }.getOrDefault(false)
            activity.runOnUiThread {
                val msg = if (ok) activity.getString(R.string.logs_saved, "Download/$name")
                else activity.getString(R.string.logs_failed)
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun section(@StringRes title: Int, rows: (LinearLayout) -> Unit) {
        container.addView(TextView(activity).apply {
            setText(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(container, android.R.attr.colorPrimary))
            setPaddingRelative((26 * dp).toInt(), (14 * dp).toInt(), (26 * dp).toInt(), (8 * dp).toInt())
        })
        val card = MaterialCardView(activity).apply {
            radius = 24 * dp
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerLow))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * dp).toInt(); marginEnd = (16 * dp).toInt() }
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list)
        container.addView(card)
        rows(list)
    }

    private fun row(parent: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, @StringRes summary: Int): ItemSettingBinding {
        val b = ItemSettingBinding.inflate(activity.layoutInflater, parent, false)
        b.icon.setImageResource(icon)
        b.iconBg.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSecondaryContainer))
        }
        b.title.setText(title)
        b.summary.setText(summary)
        parent.addView(b.root)
        return b
    }

    private fun toggleRow(
        parent: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, @StringRes summary: Int,
        key: String, default: Boolean, onChanged: () -> Unit
    ) {
        val b = row(parent, icon, title, summary)
        b.toggle.isVisible = true
        b.toggle.isChecked = prefs.getBoolean(key, default)
        b.root.setOnClickListener {
            val value = !prefs.getBoolean(key, default)
            // apply() writes to disk in the background; the in-memory value updates at once.
            prefs.edit { putBoolean(key, value) }
            b.toggle.isChecked = value
            // Let the switch animation finish before doing heavier work (reload, recreate).
            b.root.removeCallbacks(b.root.tag as? Runnable)
            val task = Runnable { onChanged() }
            b.root.tag = task
            b.root.postDelayed(task, 250)
        }
    }

    private fun valueRow(
        parent: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, @StringRes summary: Int,
        value: (() -> String)?, onClick: (refresh: () -> Unit) -> Unit
    ) {
        val b = row(parent, icon, title, summary)
        val refresh = {
            if (value != null) {
                b.value.isVisible = true
                b.value.text = value()
            }
        }
        refresh()
        b.root.setOnClickListener { onClick(refresh) }
    }

    companion object {
        const val PROJECT_URL = "https://github.com/ifoknr/NextWebUI"
    }
}
