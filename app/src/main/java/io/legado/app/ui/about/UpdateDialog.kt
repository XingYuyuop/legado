package io.legado.app.ui.about

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogUpdateBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.Download
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class UpdateDialog() : BaseDialogFragment(R.layout.dialog_update) {

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putStringArrayList("mirrors", ArrayList(updateInfo.mirrorUrls))
            putLong("size", updateInfo.size)
            putLong("createdAt", updateInfo.createdAt)
            putBoolean("isBeta", updateInfo.isBeta)
        }
    }

    private data class SourceItem(val label: String, val url: String)

    val binding by viewBinding(DialogUpdateBinding::bind)

    private val isBetaUpdate: Boolean
        get() = arguments?.getBoolean("isBeta") == true

    private val sources = ArrayList<SourceItem>()
    private val radioButtons = ArrayList<Pair<String, RadioButton>>()
    private val pingViews = HashMap<String, TextView>()
    private var selectedUrl: String? = null

    override fun onStart() {
        super.onStart()
        // 高度按内容自适应，内容区占剩余空间（超出的部分在ScrollTextView内滚动）
        setLayout(0.9f, -2)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = arguments?.getString("newVersion")
        binding.toolBar.subtitle = formatUpdateMetadata(
            size = arguments?.getLong("size") ?: 0L,
            createdAt = arguments?.getLong("createdAt") ?: 0L
        ).takeIf(String::isNotBlank)
        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            toastOnUi("没有数据")
            dismiss()
            return
        }
        binding.textView.post {
            Markwon.builder(requireContext())
                .usePlugin(GlideImagesPlugin.create(requireContext()))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(TablePlugin.create(requireContext()))
                .build()
                .setMarkdown(binding.textView, updateBody)
        }
        binding.betaActions.isVisible = true
        binding.btnBetaCancel.setOnClickListener { dismiss() }
        binding.btnBetaUpdate.setText(R.string.beta_update_now)
        binding.btnBetaUpdate.setOnClickListener {
            startDownload(selectedUrl ?: arguments?.getString("url"))
        }
        setupSourceRows()
        setupMenu()
    }

    private fun setupSourceRows() {
        val primaryUrl = arguments?.getString("url")?.takeIf(String::isNotBlank) ?: return
        sources.apply {
            clear()
            add(SourceItem(getString(R.string.source_github_direct), primaryUrl))
            arguments?.getStringArrayList("mirrors").orEmpty().forEach { mirrorUrl ->
                val host = runCatching { URI(mirrorUrl).host }.getOrNull() ?: mirrorUrl
                add(SourceItem(getString(R.string.source_mirror, host), mirrorUrl))
            }
        }
        selectedUrl = primaryUrl
        binding.sourceContainer.isVisible = true
        sources.forEach { item ->
            binding.sourceContainer.addView(createRow(item))
        }
        pingAll()
    }

    private fun createRow(item: SourceItem): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            isClickable = true
            isFocusable = true
        }
        val radioButton = RadioButton(requireContext()).apply {
            isChecked = item.url == selectedUrl
            isClickable = false
        }
        val nameView = TextView(requireContext()).apply {
            text = item.label
            textSize = 15f
            setTextColor(requireContext().getColor(R.color.primaryText))
        }
        val pingView = TextView(requireContext()).apply {
            text = getString(R.string.ping_testing)
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.secondaryText))
        }
        row.addView(radioButton)
        row.addView(nameView)
        row.addView(
            pingView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        row.setOnClickListener {
            selectedUrl = item.url
            radioButtons.forEach { (_, button) -> button.isChecked = false }
            radioButton.isChecked = true
        }
        radioButtons.add(item.url to radioButton)
        pingViews[item.url] = pingView
        return row
    }

    private fun pingAll() {
        val client = okHttpClient.newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        sources.forEach { item ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val start = SystemClock.elapsedRealtime()
                val ok = runCatching {
                    client.newCall(Request.Builder().url(item.url).head().build())
                        .execute().use { it.isSuccessful || it.code < 500 }
                }.getOrDefault(false)
                val cost = SystemClock.elapsedRealtime() - start
                withContext(Dispatchers.Main) {
                    pingViews[item.url]?.text =
                        if (ok) "${cost}ms" else getString(R.string.ping_timeout)
                }
            }
        }
    }

    private fun setupMenu() {
        binding.toolBar.inflateMenu(R.menu.app_update)
        // 下载源已由对话框内的源列表承担，菜单仅保留浏览器打开与忽略版本
        binding.toolBar.menu.apply {
            findItem(R.id.menu_download).isVisible = false
            findItem(R.id.menu_download_backup).isVisible = false
            findItem(R.id.menu_download_mirror).isVisible = false
            findItem(R.id.menu_download_alternate_mirror).isVisible = false
            findItem(R.id.menu_open_in_browser).isVisible = true
            findItem(R.id.menu_ignore_version).isVisible = !isBetaUpdate
        }
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_open_in_browser -> arguments?.getString("url")
                    ?.takeIf(String::isNotBlank)
                    ?.let { url -> requireContext().openUrl(url) }

                R.id.menu_ignore_version -> {
                    LocalConfig.ignoreUpdateVersion = arguments?.getString("newVersion")
                    toastOnUi(R.string.ignore_this_version)
                    dismiss()
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun formatUpdateMetadata(size: Long, createdAt: Long): String {
        val metadata = mutableListOf<String>()
        if (size > 0) metadata += ConvertUtils.formatFileSize(size)
        if (createdAt > 0) {
            metadata += Instant.ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        return metadata.joinToString(" · ")
    }

    private fun startDownload(url: String?) {
        val name = arguments?.getString("name")
        if (url.isNullOrBlank() || name.isNullOrBlank()) return
        Download.start(requireContext(), url, name, isAppUpdate = true)
        toastOnUi(R.string.download_start)
        dismiss()
    }

}
