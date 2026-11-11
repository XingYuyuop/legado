package io.legado.app.ui.book.toc


import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.update
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isEpub
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.globalExecutor
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.VideoPlay
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeText

class TocViewModel(application: Application) : BaseViewModel(application) {
    var bookUrl: String = ""
    var bookData = MutableLiveData<Book>()
    var chapterListCallBack: ChapterListCallBack? = null
    var bookMarkCallBack: BookmarkCallBack? = null
    var highlightCallBack: HighlightCallBack? = null
    var searchKey: String? = null

    var tocExpanded: Boolean = false
        private set

    fun initBook(bookUrl: String) {
        this.bookUrl = bookUrl
        execute {
            appDb.bookDao.getBook(bookUrl)?.let {
                tocExpanded = it.getTocExpanded()
                bookData.postValue(it)
            }
        }
    }

    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) {
        execute {
            book.update()
            LocalBook.getChapterList(book).let { chapters ->
                val durUrl = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.url
                val toc = book.filterDeletedToc(chapters)
                book.totalChapterNum = toc.size
                book.remapDurChapterIndex(durUrl, toc)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
                book.update()
                ReadBook.onChapterListUpdated(book)
                bookData.postValue(book)
            }
        }.onSuccess {
            complete.invoke(null)
        }.onError {
            complete.invoke(it)
        }
    }

    fun reverseToc(success: (book: Book) -> Unit) {
        execute {
            bookData.value?.apply {
                if (isPdf || isEpub) {
                    setReverseToc(!getReverseToc())
                    listOf(ReadBook.book, ReadManga.book, AudioPlay.book, VideoPlay.book)
                        .filter { it?.bookUrl == bookUrl }
                        .forEach { it?.setReverseToc(getReverseToc()) }
                    appDb.bookDao.updateReverseToc(bookUrl, getReverseToc())
                    return@apply
                }
                // Keep source parsing and index-based reading/cache identities unchanged.
                setReverseTocDisplay(!getReverseTocDisplay())
                listOf(ReadBook.book, ReadManga.book, AudioPlay.book, VideoPlay.book)
                    .filter { it?.bookUrl == bookUrl }
                    .forEach { it?.setReverseTocDisplay(getReverseTocDisplay()) }
                appDb.bookDao.updateReverseTocDisplay(bookUrl, getReverseTocDisplay())
            }
        }.onSuccess {
            it?.let(success)
        }
    }

    fun setTocExpanded(expanded: Boolean) {
        val book = bookData.value ?: return
        tocExpanded = expanded
        book.setTocExpanded(expanded)
        updateActiveReaderBooks(book.bookUrl, expanded)
        chapterListCallBack?.upChapterList(
            searchKey,
            resetCollapse = true,
            replaceAll = true,
        )
        globalExecutor.execute {
            runCatching {
                appDb.bookDao.updateTocExpanded(book.bookUrl, expanded)
            }.onFailure {
                AppLog.put("保存目录展开设置失败\n${it.localizedMessage}", it)
            }
        }
    }

    private fun updateActiveReaderBooks(bookUrl: String, expanded: Boolean) {
        listOf(ReadBook.book, ReadManga.book, AudioPlay.book, VideoPlay.book)
            .filter { it?.bookUrl == bookUrl }
            .forEach { it?.setTocExpanded(expanded) }
    }

    fun startChapterListSearch(newText: String?) {
        chapterListCallBack?.upChapterList(newText)
    }

    fun startBookmarkSearch(newText: String?) {
        bookMarkCallBack?.upBookmark(newText)
    }

    fun startHighlightSearch(newText: String?) {
        highlightCallBack?.upHighlight(newText)
    }

    fun upChapterListAdapter() {
        chapterListCallBack?.upAdapter()
    }

    fun deleteChapter(bookUrl: String, index: Int, success: () -> Unit) {
        execute {
            val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
            val deleted = chapters.firstOrNull { it.index == index } ?: return@execute
            val next = chapters.firstOrNull { it.index == index + 1 }
            // TXT/EPUB解析会为同名标题生成卷行，子章节删除后留下空卷，一并删除
            val prevVolume = chapters.firstOrNull { it.index == index - 1 }?.takeIf {
                it.isVolume && it.title == deleted.title && (next == null || next.isVolume)
            }
            val removeCount = if (prevVolume != null) 2 else 1
            // 记录删除的章节url，使删除在目录重新解析(自动更新/换规则)后仍然生效
            val book = appDb.bookDao.getBook(bookUrl) ?: return@execute
            book.addDeletedTocUrls(buildList {
                add(deleted.url)
                prevVolume?.let { add(it.url) }
            })
            appDb.bookChapterDao.delChapter(bookUrl, index)
            prevVolume?.let {
                appDb.bookChapterDao.delChapter(bookUrl, it.index)
            }
            chapters.filter { it.index > index }.forEach { chapter ->
                chapter.index = chapter.index - removeCount
            }
            appDb.bookChapterDao.update(*chapters.filter { it.index > index - 1 }.toTypedArray())
            // 同步书籍章节总数与进度索引，否则阅读界面仍按旧的章节列表展示
            book.apply {
                if (totalChapterNum >= removeCount) totalChapterNum -= removeCount
                if (durChapterIndex > index) durChapterIndex -= removeCount
            }
            appDb.bookDao.update(book)
            bookData.postValue(book)
            // 阅读界面立即生效
            if (ReadBook.book?.bookUrl == bookUrl) {
                if (ReadBook.durChapterIndex > index) {
                    ReadBook.durChapterIndex -= removeCount
                } else if (ReadBook.durChapterIndex == index ||
                    (prevVolume != null && ReadBook.durChapterIndex == index - 1)
                ) {
                    ReadBook.durChapterPos = 0
                }
                ReadBook.onChapterListUpdated(book)
            }
            if (ReadManga.book?.bookUrl == bookUrl) {
                if (ReadManga.durChapterIndex > index) {
                    ReadManga.durChapterIndex -= removeCount
                }
                ReadManga.onChapterListUpdated(book)
            }
            if (AudioPlay.book?.bookUrl == bookUrl) {
                if (AudioPlay.durChapterIndex > index) {
                    AudioPlay.durChapterIndex -= removeCount
                }
                AudioPlay.upData(book, true)
            }
        }.onSuccess {
            success()
        }
    }

    fun updateChapterName(bookUrl: String, index: Int, newName: String, success: () -> Unit) {
        execute {
            val chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
            if (chapter != null) {
                chapter.title = newName
                appDb.bookChapterDao.update(chapter)
            }
        }.onSuccess {
            success()
        }
    }

    fun saveBookmark(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.json"
            val doc = FileDoc.fromUri(treeUri, true)
            doc.createFileIfNotExist(fileName).writeText(
                GSON.toJson(
                    appDb.bookmarkDao.getByBook(book.name, book.author)
                )
            )
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    fun saveBookmarkMd(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.md"
            val treeDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = treeDoc.createFileIfNotExist(fileName)
                .openOutputStream()
                .getOrThrow()
            fileDoc.use { outputStream ->
                outputStream.write("## ${book.name} ${book.author}\n\n".toByteArray())
                appDb.bookmarkDao.getByBook(book.name, book.author).forEach {
                    outputStream.write("#### ${it.chapterName}\n\n".toByteArray())
                    outputStream.write("###### 原文\n ${it.bookText}\n\n".toByteArray())
                    outputStream.write("###### 摘要\n ${it.content}\n\n".toByteArray())
                }
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    interface ChapterListCallBack {
        fun upChapterList(
            searchKey: String?,
            resetCollapse: Boolean = false,
            replaceAll: Boolean = false,
        )

        fun clearDisplayTitle()

        fun upAdapter()
    }

    interface BookmarkCallBack {
        fun upBookmark(searchKey: String?)
    }

    interface HighlightCallBack {
        fun upHighlight(searchKey: String?)
    }
}
