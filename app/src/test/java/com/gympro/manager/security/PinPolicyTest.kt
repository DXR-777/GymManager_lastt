package com.gympro.manager.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPolicyTest {

    @Test fun weakPins_areRejected() {
        listOf("000000", "111111", "123456", "654321", "345678").forEach {
            assertTrue("$it should be weak", PinPolicy.isWeak(it))
        }
    }

    @Test fun reasonablePins_areAccepted() {
        listOf("135790", "482913", "121212", "123465").forEach {
            assertFalse("$it should be accepted", PinPolicy.isWeak(it))
        }
    }

    @Test fun lockout_startsOnlyAfterFullRound_andEscalates() {
        assertEquals(0L, PinPolicy.lockoutAfterFailure(0))
        assertEquals(0L, PinPolicy.lockoutAfterFailure(4))
        assertEquals(30_000L, PinPolicy.lockoutAfterFailure(5))
        assertEquals(0L, PinPolicy.lockoutAfterFailure(6))
        assertEquals(60_000L, PinPolicy.lockoutAfterFailure(10))
        assertEquals(300_000L, PinPolicy.lockoutAfterFailure(15))
    }

    @Test fun lockout_capsAtOneHour() {
        assertEquals(3_600_000L, PinPolicy.lockoutAfterFailure(30))
        assertEquals(3_600_000L, PinPolicy.lockoutAfterFailure(100))
    }

    @Test fun attemptsLeft_countsWithinRound() {
        assertEquals(5, PinPolicy.attemptsLeft(0))
        assertEquals(4, PinPolicy.attemptsLeft(1))
        assertEquals(1, PinPolicy.attemptsLeft(4))
    }

    @Test fun remaining_countsDown_andSurvivesClockRollback() {
        assertEquals(20_000L, PinPolicy.remainingLockoutMs(1_000L, 30_000L, 11_000L))
        assertEquals(0L, PinPolicy.remainingLockoutMs(1_000L, 30_000L, 99_000L))
        assertEquals(30_000L, PinPolicy.remainingLockoutMs(50_000L, 30_000L, 10_000L))
        assertEquals(0L, PinPolicy.remainingLockoutMs(0L, 0L, 10_000L))
    }

    @Test fun formatDuration_usesWesternDigits() {
        assertEquals("0:27", PinPolicy.formatDuration(27_000L))
        assertEquals("0:01", PinPolicy.formatDuration(1L))
        assertEquals("1:05", PinPolicy.formatDuration(65_000L))
    }
}
