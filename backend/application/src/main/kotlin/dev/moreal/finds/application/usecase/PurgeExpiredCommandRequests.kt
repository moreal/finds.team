package dev.moreal.finds.application.usecase

import dev.moreal.finds.application.port.ClockPort
import dev.moreal.finds.application.port.CommandMaintenancePort

class PurgeExpiredCommandRequests(private val requests: CommandMaintenancePort, private val clock: ClockPort) {
  fun execute(limit: Int = 100): Int {
    require(limit in 1..1000)
    return requests.purgeExpired(clock.now(), limit)
  }
}
