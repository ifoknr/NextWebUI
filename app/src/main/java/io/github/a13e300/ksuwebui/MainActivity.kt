package io.github.a13e300.ksuwebui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityMainBinding
    private var moduleList = emptyList<Module>()
    private var searchQuery = ""
    private var loading = false
    private var page = Page.MODULES
    private var fs: FileSystemManager? = null
    private val adapter = Adapter()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var shouldRefresh = false
    private var forceUpdateCheck = false

    private enum class Page { MODULES, UPDATES, SETTINGS }

    private fun getPinnedModules(): MutableSet<String> {
        return prefs.getStringSet("pinned_modules", emptySet<String>())?.toMutableSet() ?: mutableSetOf()
    }

    private fun savePinnedModules(pinned: Set<String>) {
        prefs.edit { putStringSet("pinned_modules", pinned) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch(Dispatchers.IO) {
            AppList.getApps(this@MainActivity)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.list.setHasFixedSize(true)
        binding.list.adapter = adapter
        binding.list.itemAnimator?.changeDuration = 0
        binding.swipeRefresh.setColorSchemeColors(MaterialColors.getColor(binding.root, android.R.attr.colorPrimary))
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        )
        binding.swipeRefresh.setOnRefreshListener {
            forceUpdateCheck = true
            refresh()
        }

        setupSearch()

        SettingsPage(this, binding.settingsContainer, prefs) { refresh() }.build()

        binding.bottomNav.setOnItemSelectedListener { item ->
            showPage(
                when (item.itemId) {
                    R.id.nav_updates -> Page.UPDATES
                    R.id.nav_settings -> Page.SETTINGS
                    else -> Page.MODULES
                }
            )
            true
        }
        binding.bottomNav.setOnItemReselectedListener {
            if (page != Page.SETTINGS) binding.list.smoothScrollToPosition(0)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != Page.MODULES) {
                    binding.bottomNav.selectedItemId = R.id.nav_modules
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        savedInstanceState?.getString("page")?.let { saved ->
            Page.entries.firstOrNull { it.name == saved }?.let { page = it }
        }
        // Selecting the item fires the listener, which calls showPage().
        binding.bottomNav.selectedItemId = when (page) {
            Page.MODULES -> R.id.nav_modules
            Page.UPDATES -> R.id.nav_updates
            Page.SETTINGS -> R.id.nav_settings
        }
        showPage(page)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
    }

    private fun setupSearch() {
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                binding.searchClear.isVisible = !s.isNullOrEmpty()
                submitList()
            }
        })
        binding.search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }
        binding.searchClear.setOnClickListener { binding.search.text?.clear() }
    }

    private fun showPage(p: Page) {
        page = p
        binding.listPage.isVisible = p != Page.SETTINGS
        binding.settingsPage.isVisible = p == Page.SETTINGS
        binding.title.setText(if (p == Page.UPDATES) R.string.nav_updates else R.string.app_name)
        if (p != Page.SETTINGS) submitList()
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
        if (moduleList.isEmpty()) showInfo(R.string.loading)
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
        val source = if (page == Page.UPDATES) moduleList.filter { it.update != null } else moduleList
        val filtered = source.filter { it.matches(searchQuery) }
        adapter.submitList(filtered)
        val updates = moduleList.count { it.update != null }
        binding.subtitle.text = when {
            moduleList.isEmpty() -> ""
            page == Page.UPDATES -> resources.getQuantityString(R.plurals.updates_count, updates, updates)
            else -> {
                val count = resources.getQuantityString(R.plurals.modules_count, moduleList.size, moduleList.size)
                if (updates > 0) count + "  ·  " + resources.getQuantityString(R.plurals.updates_count, updates, updates)
                else count
            }
        }
        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_updates)
        badge.isVisible = updates > 0
        badge.number = updates
        when {
            loading && moduleList.isEmpty() -> showInfo(R.string.loading)
            moduleList.isEmpty() -> showInfo(R.string.no_modules)
            page == Page.UPDATES && source.isEmpty() -> showInfo(R.string.no_updates)
            filtered.isEmpty() -> showInfo(R.string.no_results)
            else -> showInfo(null)
        }
    }

    private fun sortModules(list: List<Module>) =
        list.sortedWith(compareByDescending<Module> { it.pinned }.thenBy { it.name.lowercase(Locale.ROOT) })

    private fun withUpdates(list: List<Module>) = list.map { it.copy(update = UpdateChecker.cached(it.id)) }

    override fun onServiceAvailable(fs: FileSystemManager) {
        this.fs = fs
        val showDisabled = prefs.getBoolean(Prefs.SHOW_DISABLED, false)
        val webUIOnly = prefs.getBoolean(Prefs.WEBUI_ONLY, false)
        val checkUpdates = prefs.getBoolean(Prefs.CHECK_UPDATES, true)
        val force = forceUpdateCheck
        forceUpdateCheck = false
        if (force) BannerLoader.clear()
        App.executor.submit {
            val mods = runCatching {
                ModuleLoader.load(fs, showDisabled, webUIOnly, getPinnedModules())
            }.getOrDefault(emptyList())
            val sorted = sortModules(withUpdates(mods))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                moduleList = sorted
                binding.swipeRefresh.isRefreshing = false
                submitList()
            }
            if (checkUpdates && UpdateChecker.check(mods, force)) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    moduleList = withUpdates(moduleList)
                    submitList()
                }
            }
        }
    }

    override fun onLaunchFailed() {
        loading = false
        moduleList = emptyList()
        adapter.submitList(emptyList())
        binding.subtitle.text = ""
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

    private fun openWebUI(item: Module) {
        shouldRefresh = true
        startActivity(
            Intent(this, WebUIActivity::class.java)
                .setData("ksuwebui://webui/${item.id}".toUri())
                .putExtra("id", item.id)
                .putExtra("name", item.name)
        )
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    private fun showUpdate(item: Module) {
        val info = item.update ?: return
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.update_message, item.version, info.version))
            .setNegativeButton(android.R.string.cancel, null)
        info.zipUrl?.let { url -> builder.setPositiveButton(R.string.download) { _, _ -> openUrl(url) } }
        info.changelog?.let { url -> builder.setNeutralButton(R.string.changelog) { _, _ -> openUrl(url) } }
        builder.show()
    }

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    private object ModuleDiff : DiffUtil.ItemCallback<Module>() {
        override fun areItemsTheSame(oldItem: Module, newItem: Module) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Module, newItem: Module) = oldItem == newItem
    }

    inner class Adapter : ListAdapter<Module, ViewHolder>(ModuleDiff) {

        private fun ViewHolder.item(): Module? = currentList.getOrNull(bindingAdapterPosition)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val holder = ViewHolder(ItemModuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            val b = holder.binding
            b.root.setOnClickListener {
                val item = holder.item() ?: return@setOnClickListener
                if (item.hasWebUI) openWebUI(item)
            }
            b.root.setOnLongClickListener { v ->
                val item = holder.item() ?: return@setOnLongClickListener false
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                togglePin(item)
                true
            }
            b.btnOpen.setOnClickListener { holder.item()?.let { openWebUI(it) } }
            b.btnAction.setOnClickListener { holder.item()?.let { ActionRunner.run(this@MainActivity, it) } }
            b.btnSupport.setOnClickListener { holder.item()?.support?.let { openUrl(it) } }
            b.btnDonate.setOnClickListener { holder.item()?.donate?.let { openUrl(it) } }
            b.updateChip.setOnClickListener { holder.item()?.let { showUpdate(it) } }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val b = holder.binding
            BannerLoader.bind(this@MainActivity, b.banner, item, fs)
            b.name.text = item.name
            b.meta.text = listOf(item.version, item.author)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
                .ifEmpty { getString(R.string.unknown) }
            b.desc.text = item.desc
            b.desc.isVisible = item.desc.isNotBlank()
            b.pin.isVisible = item.pinned
            b.updateChip.isVisible = item.update != null
            b.btnOpen.isVisible = item.hasWebUI
            b.btnAction.isVisible = item.hasAction
            b.btnSupport.isVisible = item.support != null
            b.btnDonate.isVisible = item.donate != null
            b.buttons.isVisible = item.hasWebUI || item.hasAction || item.support != null || item.donate != null
            b.root.alpha = if (item.disabled) 0.55f else 1f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
