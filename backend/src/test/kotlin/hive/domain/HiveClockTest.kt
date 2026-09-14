package hive.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@DisplayName("HiveClock")
class HiveClockTest {

    @Test
    fun `the production clock truncates now to the minute`() {
        val before = Instant.now().truncatedTo(ChronoUnit.MINUTES)

        val now = HiveClock.SYSTEM.nowUtcToMinute()

        assertEquals(0, now.nano, "no sub-second component")
        assertEquals(now, now.truncatedTo(ChronoUnit.MINUTES), "no seconds component")
        assertTrue(
            Duration.between(before, now).abs() <= Duration.ofMinutes(1),
            "the clock should report roughly the current minute",
        )
    }

    @Test
    fun `a fixed clock is frozen and truncated`() {
        val clock = HiveClock.fixedAt(Instant.parse("2026-09-13T18:30:47.500Z"))

        assertEquals(Instant.parse("2026-09-13T18:30:00Z"), clock.nowUtcToMinute())
        assertEquals(clock.nowUtcToMinute(), clock.nowUtcToMinute(), "a fixed clock never advances")
    }

    @Test
    fun `it is a fun interface, so a test can supply one as a lambda`() {
        val clock = HiveClock { Instant.EPOCH }

        assertEquals(Instant.EPOCH, clock.nowUtcToMinute())
    }
}
