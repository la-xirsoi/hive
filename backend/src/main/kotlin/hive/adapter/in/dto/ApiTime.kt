package hive.adapter.`in`.dto

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The one timestamp format the API publishes.
 *
 * `docs/api-contract.md` spells every timestamp `2026-09-13T18:30:00Z` --
 * ISO-8601, UTC, seconds always present. `Instant.toString()` gets that right
 * today but is documented to emit the *shortest* representation, so the format
 * is pinned here rather than inherited from a `toString` that could change.
 */
private val API_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** Render this instant in the contract's timestamp format. */
fun Instant.toApiTimestamp(): String = API_TIMESTAMP.format(this)
