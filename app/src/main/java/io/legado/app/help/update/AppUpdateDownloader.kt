package io.legado.app.help.update

import android.net.Uri
import android.os.SystemClock
import io.legado.app.R
import io.legado.app.help.http.getProxyClient
import io.legado.app.utils.externalFiles
import io.legado.app.utils.openFileUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新下载器
 * OkHttp流式下载，进度与速度通过[state]暴露给界面
 * 下载仅存活于应用进程内，进程被杀即终止
 *
 * 局域网代理地址(127/192开头)同时兼容两种模式，第一模式失败时静默切换：
 * 1. HTTP/SOCKS代理(Clash等)：CONNECT隧道下载目标地址
 * 2. URL前缀网关(gh-proxy等)：代理地址 + "/" + 目标地址 直连下载
 */
object AppUpdateDownloader {

    enum class Status { RUNNING, COMPLETED, FAILED, CANCELED }

    data class State(
        val status: Status,
        val fileName: String,
        val bytes: Long = 0,
        val total: Long = 0,
        val speedBytesPerSec: Long = 0,
        val file: File? = null,
        val error: String? = null,
    )

    data class Attempt(
        val url: String,
        val proxy: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * 按下载方式链依次尝试，失败静默切换下一种方式
     * attempts由调用方按优先级组装：选中的代理(CONNECT/前缀网关) → 镜像 → 直连
     */
    fun start(fileName: String, attempts: List<Attempt>) {
        job?.cancel()
        job = scope.launch {
            val dir = File(appCtx.externalFiles, "updates").apply { mkdirs() }
            val file = File(dir, fileName)
            val temp = File(dir, "$fileName.part")
            try {
                _state.value = State(Status.RUNNING, fileName)
                var lastError: Exception? = null
                var completed = false
                for ((index, attempt) in attempts.withIndex()) {
                    try {
                        val client = proxyClient(attempt.proxy)
                        val total = downloadTo(client, attempt.url, temp, fileName)
                        if (file.exists()) {
                            file.delete()
                        }
                        if (!temp.renameTo(file)) {
                            error("无法保存下载文件")
                        }
                        _state.value = State(
                            Status.COMPLETED,
                            fileName,
                            file.length(),
                            total,
                            file = file
                        )
                        completed = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        temp.delete()
                        if (index < attempts.lastIndex) {
                            // 静默切换下一种下载方式
                            _state.value = State(Status.RUNNING, fileName)
                        }
                    }
                }
                if (!completed) {
                    throw lastError ?: IllegalStateException("下载失败")
                }
                // 下载完成自动拉起安装，弹窗已关闭(后台下载)时同样生效
                installApk(file)
                // 状态消费完即重置，避免重开弹窗时残留
                delay(1500)
                if (_state.value?.status == Status.COMPLETED) {
                    _state.value = null
                }
            } catch (e: CancellationException) {
                temp.delete()
                // 新一轮下载已发起时不再覆盖其状态
                if (coroutineContext[Job] === job) {
                    _state.value = State(Status.CANCELED, fileName)
                }
            } catch (e: Exception) {
                temp.delete()
                if (coroutineContext[Job] === job) {
                    _state.value =
                        State(Status.FAILED, fileName, error = e.localizedMessage ?: "$e")
                }
            }
        }
    }

    private suspend fun installApk(file: File) {
        withContext(Dispatchers.Main) {
            appCtx.toastOnUi(R.string.download_success)
            appCtx.openFileUri(
                Uri.fromFile(file),
                "application/vnd.android.package-archive"
            )
        }
    }

    private fun proxyClient(proxy: String?): OkHttpClient {
        return getProxyClient(proxy).newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun downloadTo(
        client: OkHttpClient,
        url: String,
        temp: File,
        fileName: String
    ): Long {
        temp.delete()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("下载内容为空")
            val total = body.contentLength().coerceAtLeast(0)
            var bytes = 0L
            var lastSampleBytes = 0L
            var lastSampleTime = SystemClock.elapsedRealtime()
            temp.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytes += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSampleTime >= 500) {
                            _state.value = State(
                                Status.RUNNING,
                                fileName,
                                bytes,
                                total,
                                (bytes - lastSampleBytes) * 1000 / (now - lastSampleTime)
                            )
                            lastSampleBytes = bytes
                            lastSampleTime = now
                        }
                    }
                }
            }
            return total
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value?.status == Status.RUNNING) {
            _state.value = _state.value?.copy(status = Status.CANCELED)
        }
    }

}
