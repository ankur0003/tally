package com.tally

import com.tally.market.InstrumentService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpiryPickTest {
    private val nov = LocalDate.of(2026, 11, 24)
    private val dec = LocalDate.of(2026, 12, 29)
    private val jan = LocalDate.of(2027, 1, 26)
    private val expiries = listOf(dec, nov, jan)

    @Test
    fun `index options keep the nearest expiry after today`() {
        assertEquals(nov, InstrumentService.pickExpiry(expiries, LocalDate.of(2026, 11, 20), stock = false))
        assertEquals(dec, InstrumentService.pickExpiry(expiries, nov, stock = false))
    }

    @Test
    fun `stock options roll to next month inside expiry week`() {
        // 2026-11-20: Nov expiry is 4 days away, Zerodha blocks fresh MIS buys on it.
        assertEquals(dec, InstrumentService.pickExpiry(expiries, LocalDate.of(2026, 11, 20), stock = true))
        assertEquals(dec, InstrumentService.pickExpiry(expiries, LocalDate.of(2026, 11, 17), stock = true))
    }

    @Test
    fun `stock options keep the current month when expiry is more than a week away`() {
        assertEquals(nov, InstrumentService.pickExpiry(expiries, LocalDate.of(2026, 11, 16), stock = true))
    }

    @Test
    fun `stock options with no far enough expiry are not traded`() {
        assertNull(InstrumentService.pickExpiry(listOf(nov), LocalDate.of(2026, 11, 20), stock = true))
    }
}
