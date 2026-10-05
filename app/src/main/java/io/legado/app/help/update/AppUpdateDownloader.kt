package io.legado.app.help.update

import android.os.SystemClock
import io.legado.app.help.http.getProxyClient
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private var job: Job? = null

    fun start(
        url: String,
        fileName: String,
        proxy: String? = null,
        proxyFallbackUrl: String? = null
    ) {
        job?.cancel()
        job = scope.launch {
            val dir = File(appCtx.externalFiles, "updates").apply { mkdirs() }
            val file = File(dir, fileName)
            val temp = File(dir, "$fileName.part")
            try {
                _state.value = State(Status.RUNNING, fileName)
                // 备用地址：局域网地址按URL前缀网关拼接直连地址(gh-proxy等)
                val fallbackUrl = if (proxy != null && !proxyFallbackUrl.isNullOrBlank()) {
                    proxy.trimEnd('/') + "/" + proxyFallbackUrl
                } else {
                    null
                }
                val targets = buildList {
                    add(url)
                    if (fallbackUrl != null) {
                        add(fallbackUrl)
                    }
                }
                var lastError: Exception? = null
                var completed = false
                for ((index, target) in targets.withIndex()) {
                    try {
                        val client = proxyClient(proxy, useProxy = proxy != null && index == 0)
                        val total = downloadTo(client, target, temp, fileName)
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
                        if (index < targets.lastIndex) {
                            // 静默切换下一模式重试
                            _state.value = State(Status.RUNNING, fileName)
                        }
                    }
                }
                if (!completed) {
                    throw lastError ?: IllegalStateException("下载失败")
                }
            } catch (e: CancellationException) {
                temp.delete()
                _state.value = State(Status.CANCELED, fileName)
            } catch (e: Exception) {
                temp.delete()
                _state.value =
                    State(Status.FAILED, fileName, error = e.localizedMessage ?: "$e")
            }
        }
    }

    private fun proxyClient(proxy: String?, useProxy: Boolean): OkHttpClient {
        val base = if (useProxy && proxy != null) {
            getProxyClient(proxy)
        } else {
            getProxyClient(null)
        }
        return base.newBuilder()
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
