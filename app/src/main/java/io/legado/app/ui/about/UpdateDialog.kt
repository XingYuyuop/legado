package io.legado.app.ui.about

import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
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
import io.legado.app.help.update.AppUpdateDownloader
import io.legado.app.help.update.localProxyMirror
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.Download
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.openFileUri
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

    private data class SourceItem(
        val label: String,
        val url: String,
        val proxy: String? = null
    )

    val binding by viewBinding(DialogUpdateBinding::bind)

    private val isBetaUpdate: Boolean
        get() = arguments?.getBoolean("isBeta") == true

    private val sources = ArrayList<SourceItem>()
    private val radioButtons = ArrayList<Pair<SourceItem, RadioButton>>()
    private val pingViews = HashMap<SourceItem, TextView>()
    private var selectedItem: SourceItem? = null
    private var inAppDownloadActive = false

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
            startDownload()
        }
        binding.cbBackgroundDownload.isChecked = LocalConfig.updateBackgroundDownload
        binding.cbBackgroundDownload.setOnCheckedChangeListener { _, isChecked ->
            LocalConfig.updateBackgroundDownload = isChecked
        }
        observeDownloadState()
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
            // 局域网代理(127/192开头)：单独作为下载源，选中后经代理下载，显示端口号
            localProxyMirror()?.let { proxy ->
                val port = runCatching { URI(proxy).port }.getOrNull()
                    ?.takeIf { it > 0 }?.toString()
                    ?: proxy.substringAfterLast('/')
                add(SourceItem(getString(R.string.source_lan_proxy, port), primaryUrl, proxy))
            }
        }
        selectedItem = sources.firstOrNull()
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
            isChecked = item == selectedItem
            isClickable = false
        }
        val nameView = TextView(requireContext()).apply {
            text = item.label
            textSize = 15f
            setTextColor(requireContext().getColor(R.color.primaryText))
        }
        val pingView = TextView(requireContext()).apply {
            // 前置空格与源名称(网址)隔开
            text = " " + getString(R.string.ping_testing)
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
            selectedItem = item
            radioButtons.forEach { (_, button) -> button.isChecked = false }
            radioButton.isChecked = true
        }
        radioButtons.add(item to radioButton)
        pingViews[item] = pingView
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
                    // 代理条目探测代理服务本身是否可达，其余探测下载地址
                    client.newCall(Request.Builder().url(item.proxy ?: item.url).head().build())
                        .execute().use { it.isSuccessful || it.code < 500 }
                }.getOrDefault(false)
                val cost = SystemClock.elapsedRealtime() - start
                withContext(Dispatchers.Main) {
                    pingViews[item]?.text =
                        if (ok) " ${cost}ms" else " " + getString(R.string.ping_timeout)
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

    private fun startDownload() {
        val name = arguments?.getString("name")
        val url = selectedItem?.url ?: arguments?.getString("url")
        if (url.isNullOrBlank() || name.isNullOrBlank()) return
        // 仅当选中的是局域网代理条目时才经代理下载，其余条目(含GitHub直连)不套代理
        val proxy = selectedItem?.proxy
        if (proxy != null || !LocalConfig.updateBackgroundDownload) {
            // 局域网代理下载 或 已关闭后台下载：应用内下载并显示进度
            startInAppDownload(url, name, proxy)
        } else {
            Download.start(requireContext(), url, name, isAppUpdate = true)
            toastOnUi(R.string.download_start)
            dismiss()
        }
    }

    private fun startInAppDownload(url: String, fileName: String, proxy: String?) {
        inAppDownloadActive = true
        binding.betaActions.isVisible = false
        binding.cbBackgroundDownload.isVisible = false
        binding.sourceContainer.isVisible = false
        binding.downloadProgressContainer.isVisible = true
        binding.tvDownloadState.text = getString(R.string.downloading)
        binding.btnCancelDownload.setOnClickListener {
            AppUpdateDownloader.cancel()
        }
        // 代理模式失败时按URL前缀网关(gh-proxy等)重试原始直连地址
        AppUpdateDownloader.start(url, fileName, proxy, arguments?.getString("url"))
    }

    private fun observeDownloadState() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppUpdateDownloader.state.collect { st ->
                if (!inAppDownloadActive || st == null) return@collect
                when (st.status) {
                    AppUpdateDownloader.Status.RUNNING -> upDownloadProgress(st)

                    AppUpdateDownloader.Status.COMPLETED -> {
                        inAppDownloadActive = false
                        toastOnUi(R.string.download_success)
                        st.file?.let { file ->
                            requireContext().openFileUri(
                                Uri.fromFile(file),
                                "application/vnd.android.package-archive"
                            )
                        }
                        dismiss()
                    }

                    AppUpdateDownloader.Status.FAILED -> {
                        inAppDownloadActive = false
                        toastOnUi(
                            st.error?.takeIf(String::isNotBlank)
                                ?.let { getString(R.string.download_error) + ": " + it }
                                ?: getString(R.string.download_error)
                        )
                        showDownloadActions()
                    }

                    AppUpdateDownloader.Status.CANCELED -> {
                        inAppDownloadActive = false
                        showDownloadActions()
                    }
                }
            }
        }
    }

    private fun upDownloadProgress(st: AppUpdateDownloader.State) {
        if (st.total > 0) {
            binding.downloadProgressBar.isIndeterminate = false
            binding.downloadProgressBar.max = 100
            binding.downloadProgressBar.progress =
                ((st.bytes * 100) / st.total).toInt().coerceIn(0, 100)
            binding.tvDownloadState.text = buildString {
                append((st.bytes * 100 / st.total).toInt().coerceIn(0, 100))
                append("% · ")
                append(ConvertUtils.formatFileSize(st.speedBytesPerSec))
                append("/s · ")
                append(ConvertUtils.formatFileSize(st.bytes))
                append("/")
                append(ConvertUtils.formatFileSize(st.total))
            }
        } else {
            binding.downloadProgressBar.isIndeterminate = true
            binding.tvDownloadState.text = buildString {
                append(ConvertUtils.formatFileSize(st.speedBytesPerSec))
                append("/s · ")
                append(ConvertUtils.formatFileSize(st.bytes))
            }
        }
    }

    private fun showDownloadActions() {
        binding.downloadProgressContainer.isVisible = false
        binding.betaActions.isVisible = true
        binding.cbBackgroundDownload.isVisible = true
        binding.sourceContainer.isVisible = sources.isNotEmpty()
    }

}
