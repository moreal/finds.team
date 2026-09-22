package dev.moreal.finds_team.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

class ManagedCoroutineScope : CoroutineScope, AutoCloseable {
  override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

  override fun close() {
    cancel("Application is shutting down")
  }
}
