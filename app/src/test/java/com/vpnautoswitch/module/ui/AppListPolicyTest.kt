package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListPolicyTest {

    private val labels = mapOf(
        "com.a" to "Alpha",
        "com.b" to "Bravo",
        "com.c" to "Charlie",
    )

    private fun labelOf(pkg: String): String = labels[pkg] ?: pkg

    @Test
    fun `keeps insertion order when sorting is off`() {
        val shown = AppListPolicy.display(
            packages = listOf("com.c", "com.a", "com.b"),
            labelOf = ::labelOf,
        )
        assertEquals(listOf("com.c", "com.a", "com.b"), shown)
    }

    @Test
    fun `sorts by label when asked`() {
        val shown = AppListPolicy.display(
            packages = listOf("com.c", "com.a", "com.b"),
            labelOf = ::labelOf,
            sortByName = true,
        )
        assertEquals(listOf("com.a", "com.b", "com.c"), shown)
    }

    @Test
    fun `filters by label and package name`() {
        val byLabel = AppListPolicy.display(
            packages = listOf("com.a", "com.b"),
            labelOf = ::labelOf,
            query = "brav",
        )
        assertEquals(listOf("com.b"), byLabel)

        val byPackage = AppListPolicy.display(
            packages = listOf("com.a", "com.b"),
            labelOf = ::labelOf,
            query = "COM.A",
        )
        assertEquals(listOf("com.a"), byPackage)
    }

    @Test
    fun `blank query keeps everything`() {
        val shown = AppListPolicy.display(
            packages = listOf("com.a", "com.b"),
            labelOf = ::labelOf,
            query = "   ",
        )
        assertEquals(2, shown.size)
    }

    @Test
    fun `search box only appears once the list is long enough`() {
        assertFalse(AppListPolicy.shouldShowSearch(AppListPolicy.SEARCH_THRESHOLD - 1))
        assertTrue(AppListPolicy.shouldShowSearch(AppListPolicy.SEARCH_THRESHOLD))
    }
}
