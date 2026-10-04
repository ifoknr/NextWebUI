package io.github.a13e300.ksuwebui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import androidx.core.net.toUri
import androidx.core.content.edit

class MainActivity : AppCompatActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityMainBinding
    private var moduleList = emptyList<Module>()
    private var searchQuery = ""
    private var loading = false
    private val adapter = Adapter()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var shouldRefresh = false

    private fun getPinnedModules(): MutableSet<String> {
        return prefs.getStringSet("pinned_modules", emptySet<String>())?.toMutableSet() ?: mutableSetOf()
    }

    private fun savePinnedModules(pinned: Set<String>) {
        prefs.edit { putStringSet("pinned_modules", pinned) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge to edge
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        lifecycleScope.launch(Dispatchers.IO) {
            AppList.getApps(this@MainActivity)
        }

        // Add insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.appbar) { v, insets ->
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, top = cutoutAndBars.top, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }
        val listBottomPadding = binding.list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { v, insets ->
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, bottom = listBottomPadding + cutoutAndBars.bottom, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }

        binding.list.setHasFixedSize(true)
        binding.list.adapter = adapter
        binding.swipeRefresh.setColorSchemeColors(
            com.google.android.material.color.MaterialColors.getColor(binding.root, android.R.attr.colorPrimary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        )
        binding.swipeRefresh.setOnRefreshListener {
            refresh()
        }
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        (menu.findItem(R.id.search).actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean {
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(text: String?): Boolean {
                    searchQuery = text.orEmpty().trim()
                    submitList()
                    return true
                }
            })
        }
        menu.findItem(R.id.enable_webview_debugging).apply {
            isChecked = prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("enable_web_debugging", newValue) }
                it.isChecked = newValue
                true
            }
        }
        menu.findItem(R.id.show_disabled).apply {
            isChecked = prefs.getBoolean("show_disabled", false)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("show_disabled", newValue) }
                it.isChecked = newValue
                refresh()
                true
            }
        }
        menu.findItem(R.id.dynamic_colors).apply {
            // Wallpaper colors need Android 12+
            isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            isChecked = prefs.getBoolean(App.PREF_DYNAMIC_COLORS, false)
            setOnMenuItemClickListener {
                prefs.edit(commit = true) { putBoolean(App.PREF_DYNAMIC_COLORS, !it.isChecked) }
                recreate()
                true
            }
        }
        menu.findItem(R.id.enable_monet).apply {
            isChecked = prefs.getBoolean("enable_monet", true)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("enable_monet", newValue) }
                it.isChecked = newValue
                true
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (shouldRefresh) {
            refresh()
            shouldRefresh = false
        }
    }

    private fun refresh() {
        binding.swipeRefresh.isRefreshing = true
        loading = true
        if (moduleList.isEmpty()) {
            showInfo(R.string.loading)
        }
        FileSystemService.start(this)
    }

    private fun showInfo(text: Int?) {
        binding.empty.isVisible = text != null
        if (text != null) {
            binding.info.setText(text)
            binding.emptyIcon.isVisible = text != R.string.loading
        }
    }

    private fun Module.matches(q: String): Boolean {
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) ||
                id.contains(q, ignoreCase = true) ||
                author.contains(q, ignoreCase = true) ||
                desc.contains(q, ignoreCase = true)
    }

    private fun submitList() {
        val filtered = moduleList.filter { it.matches(searchQuery) }
        adapter.submitList(filtered)
        binding.toolbar.subtitle = if (moduleList.isEmpty()) null
        else resources.getQuantityString(R.plurals.modules_count, moduleList.size, moduleList.size)
        when {
            loading && moduleList.isEmpty() -> showInfo(R.string.loading)
            moduleList.isEmpty() -> showInfo(R.string.no_modules)
            filtered.isEmpty() -> showInfo(R.string.no_results)
            else -> showInfo(null)
        }
    }

    private fun sortModules(list: List<Module>) =
        list.sortedWith(compareByDescending<Module> { it.pinned }.thenBy { it.name.lowercase(Locale.ROOT) })

    override fun onServiceAvailable(fs: FileSystemManager) {
        App.executor.submit {
            val mods = mutableListOf<Module>()
            val showDisabled = prefs.getBoolean("show_disabled", false)
            val pinnedIds = getPinnedModules()
            fs.getFile("/data/adb/modules").listFiles()?.forEach { f ->
                if (!f.isDirectory) return@forEach
                if (!isValidModuleId(f.name)) return@forEach
                if (!fs.getFile(f, "webroot").isDirectory) return@forEach
                val propFile = fs.getFile(f, "module.prop")
                if (!propFile.exists()) return@forEach
                val disabled = fs.getFile(f, "disable").exists()
                if (disabled && !showDisabled) return@forEach
                val props = runCatching { parseModuleProp(propFile.newInputStream()) }.getOrDefault(emptyMap())
                val id = f.name
                mods.add(
                    Module(
                        name = props["name"]?.takeIf { it.isNotBlank() } ?: id,
                        id = id,
                        desc = props["description"].orEmpty(),
                        author = props["author"].orEmpty(),
                        version = props["version"].orEmpty(),
                        disabled = disabled,
                        pinned = id in pinnedIds
                    )
                )
            }
            val sorted = sortModules(mods)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                moduleList = sorted
                binding.swipeRefresh.isRefreshing = false
                submitList()
            }
        }
    }

    override fun onLaunchFailed() {
        loading = false
        moduleList = emptyList()
        adapter.submitList(emptyList())
        binding.toolbar.subtitle = null
        showInfo(R.string.please_grant_root)
        binding.swipeRefresh.isRefreshing = false
    }

    private fun togglePin(item: Module) {
        val pinnedIds = getPinnedModules()
        val pinned = item.id !in pinnedIds
        if (pinned) pinnedIds.add(item.id) else pinnedIds.remove(item.id)
        savePinnedModules(pinnedIds)
        moduleList = sortModules(moduleList.map { if (it.id == item.id) it.copy(pinned = pinned) else it })
        submitList()
        Toast.makeText(this, if (pinned) R.string.pin_added else R.string.pin_removed, Toast.LENGTH_SHORT).show()
    }

    data class Module(
        val name: String,
        val id: String,
        val desc: String,
        val author: String,
        val version: String,
        val disabled: Boolean = false,
        val pinned: Boolean = false
    )

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    private object ModuleDiff : DiffUtil.ItemCallback<Module>() {
        override fun areItemsTheSame(oldItem: Module, newItem: Module) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Module, newItem: Module) = oldItem == newItem
    }

    inner class Adapter : ListAdapter<Module, ViewHolder>(ModuleDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val holder = ViewHolder(
                ItemModuleBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            holder.binding.root.setOnClickListener {
                val item = currentList.getOrNull(holder.bindingAdapterPosition) ?: return@setOnClickListener
                shouldRefresh = true
                startActivity(
                    Intent(this@MainActivity, WebUIActivity::class.java)
                        .setData("ksuwebui://webui/${item.id}".toUri())
                        .putExtra("id", item.id)
                        .putExtra("name", item.name)
                )
            }
            holder.binding.root.setOnLongClickListener { v ->
                val item = currentList.getOrNull(holder.bindingAdapterPosition) ?: return@setOnLongClickListener false
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                togglePin(item)
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            b.name.text = item.name
            b.avatar.text = item.name.avatarLetter()
            b.meta.text = listOf(item.version, item.author)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
                .ifEmpty { getString(R.string.unknown) }
            b.desc.text = item.desc
            b.desc.isVisible = item.desc.isNotBlank()
            b.pin.isVisible = item.pinned
            b.root.alpha = if (item.disabled) 0.55f else 1f
        }
    }

    private fun String.avatarLetter(): String {
        val i = indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return "•"
        val cp = codePointAt(i)
        return String(Character.toChars(cp)).uppercase()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
