package com.gympro.manager.utils

import com.gympro.manager.model.SubscriptionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DateUtilsTimeZoneTest {

    private lateinit var original: TimeZone

    @Before
    fun saveZone() {
        original = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(original)
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, s: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, s)
        }.timeInMillis

    @Test
    fun daysBetween_acrossSpringForward_isCalendarDays() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(2, DateUtils.daysBetween(at(2026, 3, 7, 23), at(2026, 3, 9, 1)))
    }

    @Test
    fun daysBetween_acrossFallBack_isCalendarDays() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(2, DateUtils.daysBetween(at(2026, 10, 31, 23), at(2026, 11, 2, 0, 30)))
    }

    @Test
    fun startOfDay_neverLeavesTheSameCalendarDay_inZonesWithMidnightTransition() {
        for (id in listOf("Asia/Gaza", "Asia/Hebron", "America/Sao_Paulo", "Asia/Beirut")) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            val cal = Calendar.getInstance()
            for (dayOffset in 0 until 400) {
                cal.clear()
                cal.set(2026, Calendar.JANUARY, 1 + dayOffset, 15, 0, 0)
                val probe = cal.timeInMillis
                val start = DateUtils.startOfDay(probe)
                val end = DateUtils.endOfDay(probe)
                assertEquals(id, DateUtils.dayOfMonth(probe), DateUtils.dayOfMonth(start))
                assertEquals(id, DateUtils.dayOfMonth(probe), DateUtils.dayOfMonth(end))
                assertTrue(id, start <= probe && probe <= end)
            }
        }
    }

    @Test
    fun expiry_boundary_isEndOfLocalDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Gaza"))
        val end = DateUtils.endOfDay(at(2026, 5, 10))
        assertFalse(DateUtils.isExpired(end, at(2026, 5, 10, 23, 59, 59)))
        assertTrue(DateUtils.isExpired(end, at(2026, 5, 11, 0, 0, 1)))
    }

    @Test
    fun monthlyEnd_acrossDstChange_staysOnExpectedDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val start = DateUtils.startOfDay(at(2026, 3, 15))
        val end = DateUtils.calculateEndDate(start, SubscriptionType.MONTHLY, billingAnchorDay = 15)
        assertEquals(14, DateUtils.dayOfMonth(end))
        assertEquals(30, DateUtils.daysBetween(start, end))
    }

    @Test
    fun weeklyEnd_isSixDaysAfterStart_acrossDst() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val start = DateUtils.startOfDay(at(2026, 3, 5))
        val end = DateUtils.calculateEndDate(start, SubscriptionType.WEEKLY)
        assertEquals(6, DateUtils.daysBetween(start, end))
    }

    @Test
    fun daysRemaining_changesWhenDeviceZoneChanges_documentedBehavior() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Gaza"))
        val end = DateUtils.endOfDay(at(2026, 6, 10))
        val now = at(2026, 6, 10, 12)
        assertEquals(0, DateUtils.daysRemaining(end, now))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertTrue(DateUtils.daysRemaining(end, now) in -1..1)
    }
}
