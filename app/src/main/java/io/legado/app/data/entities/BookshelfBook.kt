package io.legado.app.data.entities

import java.io.File

data class BookshelfBook(
    val bookUrl: String,
    val origin: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val customCoverUrl: String?,
    val type: Int,
    val group: Long,
    val hasUserGroup: Boolean,
    val latestChapterTime: Long,
    val durChapterTime: Long,
    val order: Int,
    val persistedCoverUrl: String? = null,
) {
    // 本地封面文件失效时回退到网络封面，避免设备同步书架全部封面空白
    val displayCover: String?
        get() = persistedCoverUrl?.takeIf { it.isNotEmpty() && File(it).isFile }
            ?: customCoverUrl?.takeIf { it.isNotEmpty() }
            ?: coverUrl

    val coverSourceOrigin: String?
        get() = if (persistedCoverUrl.isNullOrEmpty()) {
            coverSourceOrigin(origin, customCoverUrl)
        } else {
            null
        }
}
