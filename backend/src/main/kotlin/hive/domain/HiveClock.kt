package hive.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The domain's only source of time.
 *
 * No domain code calls `Instant.now()` directly; everything that needs the
 * current time takes a [HiveClock], so tests inject a fixed one and stay
 * deterministic.
 *
 * The single method already applies the minute truncation that `spec.md`
 * mandates for comment timestamps (CM-5) -- putting it in the clock means the
 * rule cannot be forgotten at a call site.
 */
fun interface HiveClock {
    /** The current instant in UTC, truncated to the minute. */
    fun nowUtcToMinute(): Instant

    companion object {
        /** The production clock. */
        val SYSTEM: HiveClock = HiveClock { Instant.now().truncatedTo(ChronoUnit.MINUTES) }

        /** A clock frozen at [instant], truncated to the minute. For tests and fixtures. */
        fun fixedAt(instant: Instant): HiveClock {
            val frozen = instant.truncatedTo(ChronoUnit.MINUTES)
            return HiveClock { frozen }
        }
    }
}
