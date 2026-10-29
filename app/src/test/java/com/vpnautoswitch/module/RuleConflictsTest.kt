package com.vpnautoswitch.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleConflictsTest {

    private fun config(
        targets: Set<String> = emptySet(),
        offTargets: Set<String> = emptySet(),
        wifiOn: Set<String> = emptySet(),
        wifiOff: Set<String> = emptySet(),
    ) = Config(targets = targets, offTargets = offTargets, wifiOn = wifiOn, wifiOff = wifiOff)

    @Test
    fun `disjoint lists report no conflict`() {
        val c = config(targets = setOf("a"), offTargets = setOf("b"))
        assertTrue(RuleConflicts.find(c).isEmpty)
    }

    @Test
    fun `same app on both sides is a conflict`() {
        val c = config(targets = setOf("a", "b"), offTargets = setOf("b", "c"))
        val conflict = RuleConflicts.find(c)
        assertEquals(setOf("b"), conflict.apps)
        assertTrue(conflict.wifi.isEmpty())
        assertEquals(1, conflict.count)
    }

    @Test
    fun `same ssid on both wifi lists is a conflict`() {
        val c = config(wifiOn = setOf("home"), wifiOff = setOf("home", "office"))
        val conflict = RuleConflicts.find(c)
        assertEquals(setOf("home"), conflict.wifi)
        assertTrue(conflict.apps.isEmpty())
    }

    @Test
    fun `saving on list removes the same entry from the off list`() {
        val c = config(targets = emptySet(), offTargets = setOf("a", "b"))
        val (next, dropped) = RuleConflicts.keepOn(c, setOf("a", "c"))
        assertEquals(1, dropped)
        assertEquals(setOf("a", "c"), next.targets)
        assertEquals(setOf("b"), next.offTargets)
    }

    @Test
    fun `saving off list wins and removes the entry from the on list`() {
        val c = config(targets = setOf("a", "b"))
        val (next, dropped) = RuleConflicts.keepOff(c, setOf("b"))
        assertEquals(1, dropped)
        assertEquals(setOf("b"), next.offTargets)
        assertEquals(setOf("a"), next.targets)
        assertTrue(RuleConflicts.find(next).isEmpty)
    }

    @Test
    fun `saving wifi lists cleans the opposite side`() {
        val on = config(wifiOff = setOf("home"))
        val (afterOn, droppedOn) = RuleConflicts.keepWifiOn(on, setOf("home"))
        assertEquals(1, droppedOn)
        assertTrue(afterOn.wifiOff.isEmpty())

        val off = config(wifiOn = setOf("home"))
        val (afterOff, droppedOff) = RuleConflicts.keepWifiOff(off, setOf("home"))
        assertEquals(1, droppedOff)
        assertTrue(afterOff.wifiOn.isEmpty())
    }

    @Test
    fun `normalize reports and clears every overlap`() {
        val c = config(
            targets = setOf("a", "b"),
            offTargets = setOf("b"),
            wifiOn = setOf("home", "office"),
            wifiOff = setOf("office"),
        )
        val (cleaned, before) = RuleConflicts.normalize(c)
        assertEquals(setOf("b"), before.apps)
        assertEquals(setOf("office"), before.wifi)
        assertTrue(RuleConflicts.find(cleaned).isEmpty)
        assertEquals(setOf("a"), cleaned.targets)
        assertEquals(setOf("b"), cleaned.offTargets)
        assertEquals(setOf("home"), cleaned.wifiOn)
        assertEquals(setOf("office"), cleaned.wifiOff)
    }

    @Test
    fun `normalize keeps a clean config untouched`() {
        val c = config(targets = setOf("a"), offTargets = setOf("b"))
        val (cleaned, before) = RuleConflicts.normalize(c)
        assertTrue(before.isEmpty)
        assertEquals(c, cleaned)
    }
}
