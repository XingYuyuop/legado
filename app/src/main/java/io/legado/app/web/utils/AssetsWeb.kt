package io.legado.app.web.utils

import android.content.res.AssetManager
import android.text.TextUtils
import fi.iki.elonen.NanoHTTPD
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream


class AssetsWeb(rootPath: String) {
    private val assetManager: AssetManager = appCtx.assets
    private var rootPath = "web"

    init {
        if (!TextUtils.isEmpty(rootPath)) {
            this.rootPath = rootPath
        }
    }

    @Throws(IOException::class)
    fun getResponse(path: String, acceptEncoding: String? = null): NanoHTTPD.Response {
        val path1 = (rootPath + path).replace("/+".toRegex(), File.separator)
        val bytes = assetManager.open(path1).use { it.readBytes() }
        val mime = getMimeType(path1)
        // 文本类资源且浏览器支持gzip时压缩返回，减小传输体积提升打开速度
        return if (isCompressible(path1) && acceptEncoding?.contains("gzip", true) == true) {
            val gzipBytes = ByteArrayOutputStream(bytes.size / 2).use { bos ->
                GZIPOutputStream(bos).use { it.write(bytes) }
                bos.toByteArray()
            }
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mime,
                ByteArrayInputStream(gzipBytes),
                gzipBytes.size.toLong()
            ).apply {
                addHeader("Content-Encoding", "gzip")
            }
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mime,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        }
    }

    private fun isCompressible(path: String): Boolean {
        val suffix = path.substringAfterLast('.', "").lowercase()
        return suffix in setOf("html", "htm", "js", "css", "svg", "json", "txt", "md", "xml")
    }

    private fun getMimeType(path: String): String {
        val dot = path.lastIndexOf(".")
        if (dot < 0) return "text/html"
        val suffix = path.substring(dot)
        return when {
            suffix.equals(".html", ignoreCase = true)
                    || suffix.equals(".htm", ignoreCase = true) -> "text/html"
            suffix.equals(".js", ignoreCase = true) -> "text/javascript"
            suffix.equals(".mjs", ignoreCase = true) -> "text/javascript"
            suffix.equals(".css", ignoreCase = true) -> "text/css"
            suffix.equals(".json", ignoreCase = true) -> "application/json"
            suffix.equals(".xml", ignoreCase = true) -> "application/xml"
            suffix.equals(".txt", ignoreCase = true)
                    || suffix.equals(".md", ignoreCase = true) -> "text/plain"
            suffix.equals(".ico", ignoreCase = true) -> "image/x-icon"
            suffix.equals(".jpg", ignoreCase = true)
                    || suffix.equals(".jpeg", ignoreCase = true) -> "image/jpeg"
            suffix.equals(".png", ignoreCase = true) -> "image/png"
            suffix.equals(".gif", ignoreCase = true) -> "image/gif"
            suffix.equals(".webp", ignoreCase = true) -> "image/webp"
            suffix.equals(".svg", ignoreCase = true) -> "image/svg+xml"
            suffix.equals(".woff", ignoreCase = true) -> "font/woff"
            suffix.equals(".woff2", ignoreCase = true) -> "font/woff2"
            suffix.equals(".ttf", ignoreCase = true) -> "font/ttf"
            suffix.equals(".otf", ignoreCase = true) -> "font/otf"
            suffix.equals(".mp3", ignoreCase = true) -> "audio/mpeg"
            suffix.equals(".mp4", ignoreCase = true) -> "video/mp4"
            suffix.equals(".pdf", ignoreCase = true) -> "application/pdf"
            else -> "text/html"
        }
    }
}
