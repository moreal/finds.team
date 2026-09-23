package dev.moreal.finds.application.port

import java.time.Instant

/** Terminalize abandoned runs only; never fetch a source or remove a current lease. */
fun interface CrawlMaintenancePort {
  fun expireAbandoned(now: Instant, startedBefore: Instant, limit: Int): Int
}
