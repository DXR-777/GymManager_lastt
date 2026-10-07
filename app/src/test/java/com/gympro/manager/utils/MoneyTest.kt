package com.gympro.manager.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test fun roundMoney_removesBinaryNoise() {
        assertEquals(0.3, (0.1 + 0.2).roundMoney(), 0.0)
        assertEquals(0.2, (10.3 - 10.1).roundMoney(), 0.0)
    }

    @Test fun roundMoney_roundsHalfUpOnDecimalText() {
        assertEquals(10.01, 10.005.roundMoney(), 0.0)
        assertEquals(10.0, 10.004.roundMoney(), 0.0)
    }

    @Test fun roundMoney_leavesNonFiniteUntouched() {
        assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY.roundMoney(), 0.0)
    }

    @Test fun toSafeMoneyOrNull_parsesAndRounds() {
        assertEquals(50.0, "50".toSafeMoneyOrNull()!!, 0.0)
        assertEquals(10.01, "10.005".toSafeMoneyOrNull()!!, 0.0)
        assertEquals(12.5, "\u0661\u0662\u066B\u0665".toSafeMoneyOrNull()!!, 0.0) // ١٢٫٥
    }

    @Test fun toSafeMoneyOrNull_rejectsNonNumbersAndNonFinite() {
        assertNull("".toSafeMoneyOrNull())
        assertNull("abc".toSafeMoneyOrNull())
        assertNull("NaN".toSafeMoneyOrNull())
        assertNull("Infinity".toSafeMoneyOrNull())
    }

    @Test fun currencyFormat_handlesLargeAmountsWithoutIntOverflow() {
        assertEquals("\u2066$ 30000000\u2069", CurrencyFormatter.format(30_000_000.0, "$"))
        assertEquals("\u2066$ 1234.50\u2069", CurrencyFormatter.format(1234.5, "$"))
    }
}
