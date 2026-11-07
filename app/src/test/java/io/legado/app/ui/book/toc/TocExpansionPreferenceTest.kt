package io.legado.app.ui.book.toc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocExpansionPreferenceTest {

    /**
     * 目录展开按钮为会话级开关：默认收缩，点击展开，再点收回，不按书持久化。
     */
    @Test
    fun `toc expansion is a transient toggle defaulting to collapsed`() {
        val menu = projectFile("src/main/res/menu/book_toc.xml").readText()
        assertTrue(menu.contains("@+id/menu_expand_toc"))
        assertTrue(menu.contains("android:checkable=\"true\""))
        assertTrue(menu.contains("@string/expand_toc"))

        val activity = projectFile("src/main/java/io/legado/app/ui/book/toc/TocActivity.kt").readText()
        assertTrue(activity.contains("menu.findItem(R.id.menu_expand_toc)?.isChecked = viewModel.tocExpanded"))
        assertTrue(activity.contains("viewModel.setTocExpanded(expanded)"))

        val viewModel = projectFile("src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt").readText()
        assertTrue(viewModel.contains("var tocExpanded: Boolean = false"))
        val setBlock = viewModel.substringAfter("fun setTocExpanded")
            .substringBefore("fun startChapterListSearch")
        assertTrue(setBlock.contains("tocExpanded = expanded"))
        assertTrue(setBlock.contains("resetCollapse = true"))
        assertFalse(setBlock.contains("bookDao"))
        assertFalse(setBlock.contains("setTocExpanded(book"))

        val fragment = projectFile("src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt").readText()
        assertFalse(fragment.contains("TocExpanded"))
        assertTrue(fragment.contains("viewModel.tocExpanded"))

        val book = projectFile("src/main/java/io/legado/app/data/entities/Book.kt").readText()
        assertFalse(book.contains("tocExpanded"))

        val dao = projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt").readText()
        assertFalse(dao.contains("TocExpanded"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
