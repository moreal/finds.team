package dev.moreal.finds.application.model

import java.time.Instant

/**
 * Discovery filters and cursors share the finite, microsecond-precision storage contract.
 * Bounds use ISO/proleptic Gregorian years, including year zero, matching PostgreSQL timestamps.
 * https://github.com/postgres/postgres/blob/REL_17_STABLE/src/include/datatype/timestamp.h
 */
object DiscoveryTimestamp {
  private val minimum = Instant.parse("-4713-11-24T00:00:00Z")
  private val endExclusive = Instant.parse("+294277-01-01T00:00:00Z")

  fun supports(value: Instant): Boolean =
    value >= minimum && value < endExclusive && value.nano % 1_000 == 0
}
