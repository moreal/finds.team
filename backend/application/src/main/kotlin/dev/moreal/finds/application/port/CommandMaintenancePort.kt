package dev.moreal.finds.application.port

import java.time.Instant

/** Deletes only expired ORDINARY command rows; sensitive AUDIT rows are never eligible. */
fun interface CommandMaintenancePort { fun purgeExpired(now: Instant, limit: Int): Int }
