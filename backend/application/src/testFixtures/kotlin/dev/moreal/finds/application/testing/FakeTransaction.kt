package dev.moreal.finds.application.testing

import dev.moreal.finds.application.audit.AuditEvent
import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.port.*
import dev.moreal.finds.domain.career.CareerSite
import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.identity.User
import java.time.Instant

/**
 * In-memory contract fixture. Serializes whole transactions (stronger than PostgreSQL's per-key
 * arbitration), clones at entry and publishes only after success. It does not simulate SQL locking;
 * the persistence adapter still needs concurrent database tests. No provider I/O is performed here.
 */
class FakeTransaction(
  initialSites: List<CareerSite> = emptyList(),
  var auditFailure: (() -> Unit)? = null,
  initialUsers: List<User> = emptyList(),
) : TransactionPort {
  private var state = State(initialSites, FakeIdentityState(initialUsers))
  private var executing = false
  val sites: List<CareerSite> get() = synchronized(this) { state.sites.sites.toList() }
  val auditEvents: List<AuditEvent> get() = synchronized(this) { state.audit.toList() }
  val completedRequests: Map<CommandRequestKey, StoredCommandResult>
    get() = synchronized(this) { state.requests.mapNotNull { (key, row) -> row.result?.let { key to it } }.toMap() }
  val outboxMessages: List<EnqueuedMail> get() = synchronized(this) { state.outbox.toList() }
  val users: List<User> get() = synchronized(this) { state.identity.users.values.toList() }
  val otpStates: List<OtpAccountState> get() = synchronized(this) { state.identity.otps.values.toList() }
  val credentials: List<PasskeyCredential> get() = synchronized(this) { state.identity.credentials.values.toList() }
  val restrictedSessions: List<RestrictedSession> get() = synchronized(this) { state.identity.sessions.values.toList() }
  val recoveryCodes: List<RecoveryCodeHash> get() = synchronized(this) { state.identity.recoveryCodes.values.toList() }

  @Synchronized
  override fun <T> execute(block: (TransactionContext) -> T): T {
    check(!executing) { "Nested transactions are not supported" }
    executing = true
    val snapshot = state.snapshot()
    val context = Context(snapshot)
    try {
      val result = block(context)
      context.checkCompleted()
      state = snapshot
      return result
    } finally {
      context.active = false
      executing = false
    }
  }

  private class State(initialSites: List<CareerSite>, val identity: FakeIdentityState) {
    val sites = FakeCareerSiteRepository(initialSites)
    val requests = linkedMapOf<CommandRequestKey, RequestRow>()
    val audit = mutableListOf<AuditEvent>()
    val outbox = mutableListOf<EnqueuedMail>()

    fun snapshot() = State(sites.sites, identity.snapshot()).also {
      it.requests.putAll(requests)
      it.audit.addAll(audit)
      it.outbox.addAll(outbox)
    }
  }

  private data class RequestRow(val request: CommandRequest, val result: StoredCommandResult? = null)

  private inner class Context(private val snapshot: State) : TransactionContext {
    var active = true
    private val owner = Thread.currentThread()
    private val reserved = mutableSetOf<CommandRequestKey>()

    private fun checkActive() = check(active && Thread.currentThread() === owner) { "Transaction context is inactive" }

    fun checkCompleted() = check(reserved.isEmpty()) { "Uncompleted command reservation" }

    private val identityStores = snapshot.identity.stores(::checkActive)
    override val users = identityStores.userRepository
    override val otpChallenges = identityStores.otpRepository
    override val credentials = identityStores.credentialRepository
    override val restrictedSessions = identityStores.sessionRepository
    override val recoveryCodes = identityStores.recoveryRepository

    override val careerSites = object : CareerSiteRepository {
      override fun findById(id: CareerSiteId): CareerSite? { checkActive(); return snapshot.sites.findById(id) }
      override fun findByHost(host: SiteHost): CareerSite? { checkActive(); return snapshot.sites.findByHost(host) }
      override fun insert(site: NewCareerSite): InsertCareerSiteResult { checkActive(); return snapshot.sites.insert(site) }
      override fun findEnabled(): List<CareerSite> { checkActive(); return snapshot.sites.findEnabled() }
    }

    override val commandRequests = object : CommandRequestStore {
      override fun reserve(request: CommandRequest): CommandReservation {
        checkActive()
        val row = snapshot.requests[request.key]
        if (row != null) {
          if (row.request.requestHash != request.requestHash) return CommandReservation.Conflict
          return CommandReservation.Replay(checkNotNull(row.result) { "Command already reserved in this transaction" })
        }
        snapshot.requests[request.key] = RequestRow(request)
        reserved += request.key
        return CommandReservation.Reserved
      }

      override fun complete(key: CommandRequestKey, result: StoredCommandResult) {
        checkActive()
        check(reserved.remove(key)) { "Command was not reserved by this transaction" }
        snapshot.requests[key] = snapshot.requests.getValue(key).copy(result = result)
      }
    }

    override val auditLog = AuditLog { event ->
      checkActive()
      auditFailure?.invoke()
      check(snapshot.audit.none { it.id == event.id }) { "Duplicate audit event" }
      snapshot.audit += event
    }

    override val outbox = MailOutboxEnqueue { metadata, plaintext, now ->
      checkActive()
      require(metadata.expiresAt > now) { "Mail payload must expire in the future" }
      require(snapshot.outbox.none { it.metadata.id == metadata.id }) { "Duplicate mail message id" }
      snapshot.outbox += EnqueuedMail(metadata, plaintext, now)
    }
  }
}

/** Test-only plaintext capture; production outbox encrypts before persistence. Diagnostics redact it. */
class EnqueuedMail(val metadata: MailPayloadMetadata, plaintext: ByteArray, val createdAt: Instant) {
  private val bytes = plaintext.copyOf()
  val plaintext: ByteArray get() = bytes.copyOf()
  override fun toString(): String = "EnqueuedMail(<redacted>)"
}
