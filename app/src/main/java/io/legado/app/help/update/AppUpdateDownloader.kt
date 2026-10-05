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
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新下载器
 * OkHttp流式下载，支持HTTP/SOCKS代理，进度与速度通过[state]暴露给界面
 * 下载仅存活于应用进程内，进程被杀即终止
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

    fun start(url: String, fileName: String, proxy: String? = null) {
        job?.cancel()
        job = scope.launch {
            val dir = File(appCtx.externalFiles, "updates").apply { mkdirs() }
            val file = File(dir, fileName)
            val temp = File(dir, "$fileName.part")
            try {
                _state.value = State(Status.RUNNING, fileName)
                val client = getProxyClient(proxy).newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
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
                                ensureActive()
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
                    if (!temp.renameTo(file)) error("无法保存下载文件")
                    _state.value =
                        State(
                            Status.COMPLETED,
                            fileName,
                            file.length(),
                            total,
                            file = file
                        )
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

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value?.status == Status.RUNNING) {
            _state.value = _state.value?.copy(status = Status.CANCELED)
        }
    }

}
