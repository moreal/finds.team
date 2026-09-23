package dev.moreal.finds_team.security

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.*
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.command.CommandMetadata
import dev.moreal.finds.application.command.CanonicalCommandEncoder
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.identity.*
import dev.moreal.finds.graphql.GlobalIdCodec
import dev.moreal.finds.graphql.NodeType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.webauthn.api.*
import org.springframework.security.web.webauthn.management.*
import org.springframework.web.util.WebUtils
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** All cryptographic verification runs before a short locked transaction. */
class WebAuthnCeremonies(
  private val transactions: TransactionPort, private val clock: ClockPort,
  private val random: SecureRandomPort, private val hashes: KeyedIdentityHashPort,
  private val settings: RelyingPartySettings, private val roles: InitialRolePolicyPort,
  private val actors: ActorResolver,
) {
  private val converter = ObjectConverter()
  private val verifier = WebAuthnManager.createNonStrictWebAuthnManager()
  private val rp = PublicKeyCredentialRpEntity.builder().id(settings.rpId).name("finds.team").build()

  fun authenticationOptions(request: HttpServletRequest): PublicKeyCredentialRequestOptions {
    request.getSession(false)?.removeAttribute(AUTHENTICATION_COMPLETED)
    val options = PublicKeyCredentialRequestOptions.builder().rpId(settings.rpId).challenge(Bytes(random.bytes(32)))
      .timeout(Duration.ofMinutes(5)).allowCredentials(emptyList()).userVerification(UserVerificationRequirement.REQUIRED).build()
    request.session.setAttribute(AUTHENTICATION, Ceremony(issue(request, WebAuthnChallengePurpose.AUTHENTICATION, options.challenge, null), options))
    return options
  }

  internal fun authenticate(request: HttpServletRequest, credential: PublicKeyCredential<AuthenticatorAssertionResponse>): PasskeyAuthentication {
    val ceremony = request.getSession(false)?.getAttribute(AUTHENTICATION) as? Ceremony<*>
      ?: throw CeremonyRejected(request.getSession(false)?.getAttribute(AUTHENTICATION_COMPLETED) == true)
    val options = ceremony.options as? PublicKeyCredentialRequestOptions ?: throw CeremonyRejected()
    checkBinding(request, ceremony.challenge, options.challenge)
    canonicalId(credential)
    val snapshot = transactions.execute { tx ->
      val saved = tx.credentials.findById(CredentialId(credential.rawId.toBase64UrlString())) ?: throw CeremonyRejected()
      val user = tx.users.findById(saved.userId) ?: throw CeremonyRejected()
      if (user.status != UserStatus.ACTIVE) throw CeremonyRejected()
      Triple(saved, user, tx.users.findUserHandle(user.id) ?: throw CeremonyRejected())
    }
    val (saved, user, handle) = snapshot
    val response = credential.response
    if (response.userHandle == null || !MessageDigest.isEqual(handle, response.userHandle!!.bytes)) throw CeremonyRejected()
    val material = saved.material
    val validated = verify {
      val key = converter.cborMapper.readValue(material.publicKeyCose, COSEKey::class.java)
      val record = CredentialRecordImpl(NoneAttestationStatement(), true, material.backupEligible, material.backedUp,
        material.signatureCount, AttestedCredentialData(AAGUID.ZERO, credential.rawId.bytes, key), null, null, null, emptySet())
      verifier.verify(AuthenticationRequest(credential.rawId.bytes, response.authenticatorData.bytes, response.clientDataJSON.bytes,
        response.signature.bytes), AuthenticationParameters(ServerProperty(settings.allowedOrigins.map(::Origin).toSet(), settings.rpId,
          DefaultChallenge(options.challenge.bytes)), record, null, true, true)).authenticatorData ?: throw CeremonyRejected()
    }
    val normal = transactions.execute { tx ->
      val current = tx.users.lockByEmail(user.email) ?: throw CeremonyRejected()
      if (current.status != UserStatus.ACTIVE || material.id !in current.credentials) throw CeremonyRejected()
      val now = clock.now()
      if (!tx.webauthnChallenges.consume(ceremony.challenge, now)) throw CeremonyRejected(replayed = true)
      if (!tx.credentials.updateUsage(material.id, material.signatureCount, validated.signCount, validated.isFlagBS, now)) throw CeremonyRejected()
      val normal = UserSession(UserSessionId(random.uuid()), user.id, now, now.plusSeconds(43200), now)
      tx.userSessions.save(normal)
      normal
    }
    request.session.removeAttribute(AUTHENTICATION)
    request.session.setAttribute(AUTHENTICATION_COMPLETED, true)
    request.session.removeAttribute(RESTRICTED_SESSION)
    request.session.removeAttribute(REGISTRATION)
    request.session.removeAttribute(COMPLETION)
    return PasskeyAuthentication(PasskeyPrincipal(user.id, normal.id))
  }

  /** Caller has invalidated this scope while holding the same ceremony mutex. */
  internal fun clearPendingRegistration(request: HttpServletRequest, scopeId: RestrictedSessionId) {
    val session = request.getSession(false) ?: return
    synchronized(WebUtils.getSessionMutex(session)) {
      check(session.getAttribute(RESTRICTED_SESSION) == scopeId)
      session.removeAttribute(REGISTRATION)
      session.removeAttribute(RESTRICTED_SESSION)
    }
  }

  fun registrationOptions(request: HttpServletRequest,
    authentication: Authentication?, expectedUserId: String?, beginKey: String?): PublicKeyCredentialCreationOptions = synchronized(WebUtils.getSessionMutex(request.session)) {
    val scope = restricted(request)
    if (scope.scope != RestrictedSessionScope.ADDITIONAL_PASSKEY && (expectedUserId != null || beginKey != null))
      throw CeremonyRejected()
    if (scope.scope == RestrictedSessionScope.ADDITIONAL_PASSKEY) {
      val principal = actors.sessionPrincipal(authentication) ?: throw CeremonyRejected()
      val begin = request.session.getAttribute(AdditionalPasskeyController.BEGIN) as? AdditionalPasskeyController.Begin
      if (!principal.actor.hasRecentPasskeyAuthentication(clock.now()) || principal.actor.userId != scope.userId.value ||
        expectedUserId != GlobalIdCodec.encode(NodeType.User, scope.userId.value) || begin == null ||
        beginKey != begin.key.toString() || begin.scopeId != scope.id) throw CeremonyRejected()
    }
    val adapters = snapshot(scope.userId)
    val operations = Webauthn4JRelyingPartyOperations(adapters.users, adapters.credentials, rp, settings.allowedOrigins)
    operations.setCustomizeCreationOptions { it.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
      .residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build()) }
    val options = operations.createPublicKeyCredentialCreationOptions {
      UsernamePasswordAuthenticationToken.authenticated(scope.userId.value.toString(), null, emptyList())
    }
    request.session.setAttribute(REGISTRATION, Ceremony(issue(request, WebAuthnChallengePurpose.REGISTRATION, options.challenge, scope), options))
    request.session.removeAttribute(COMPLETION)
    options
  }

  fun register(request: HttpServletRequest, authentication: Authentication?, publicKey: RelyingPartyPublicKey,
    metadata: CommandMetadata): Map<String, Any> = synchronized(WebUtils.getSessionMutex(request.session)) {
    val previous = request.getSession(false)?.getAttribute(COMPLETION) as? Completion
    if (previous != null && previous.key == metadata.idempotencyKey) {
      if (clock.now() >= previous.scope.replayExpiresAt || !hashes.matches(previous.challenge.sessionBinding,
          IdentityHashPurpose.WEBAUTHN_SESSION_BINDING, previous.challenge.id.toString(), request.session.id)) throw CeremonyRejected()
      if (previous.fingerprint != registrationFingerprint(publicKey)) throw CeremonyConflict()
      // Only a server-retained verified proof may reach application same-command replay. It never
      // restores a usable restricted session and the application never returns recovery plaintext.
      return@synchronized complete(transactions, previous.scope, previous.proof, metadata, publicKey.label, authentication, previous.passkeyId)
    }
    val ceremony = request.getSession(false)?.getAttribute(REGISTRATION) as? Ceremony<*> ?: throw CeremonyRejected(replayed = previous != null)
    val options = ceremony.options as? PublicKeyCredentialCreationOptions ?: throw CeremonyRejected()
    checkBinding(request, ceremony.challenge, options.challenge)
    val scope = restricted(request)
    if (scope.id != ceremony.challenge.restrictedSessionId || scope.userId != ceremony.challenge.userId) throw CeremonyRejected()
    canonicalId(publicKey.credential)
    val adapters = snapshot(scope.userId)
    val record = verify {
      val operations = Webauthn4JRelyingPartyOperations(adapters.users, adapters.credentials, rp, settings.allowedOrigins)
      val result = operations.registerCredential(object : RelyingPartyRegistrationRequest {
        override fun getCreationOptions() = options
        override fun getPublicKey() = publicKey
      })
      val attested = converter.cborMapper.readValue(publicKey.credential.response.attestationObject.bytes, AttestationObject::class.java)
        .authenticatorData.attestedCredentialData ?: throw CeremonyRejected()
      if (!MessageDigest.isEqual(attested.credentialId, publicKey.credential.rawId.bytes)) throw CeremonyRejected()
      result
    }
    val proof = VerifiedPasskeyRegistration(scope.userId, scope.id, record.material())
    // The decorator joins consumption to the application's existing account/audit transaction.
    val completionTransactions = object : TransactionPort {
      override fun <T> execute(block: (TransactionContext) -> T): T = transactions.execute { tx ->
        val user = tx.users.findById(scope.userId) ?: throw CeremonyRejected()
        tx.users.lockByEmail(user.email) ?: throw CeremonyRejected()
        if (!tx.webauthnChallenges.consume(ceremony.challenge, clock.now())) throw CeremonyRejected(replayed = true)
        block(tx).also { result ->
          // A semantic conflict must also roll back challenge consumption, so the original
          // verified ceremony can be submitted with a fresh command key after a 409.
          if (result == CompletePasskeyEnrollmentResult.IdempotencyConflict ||
            result == CompletePasskeyRecoveryResult.IdempotencyConflict || result == SecurityChangeResult.IdempotencyConflict)
            throw CeremonyConflict()
        }
      }
    }
    val result = complete(completionTransactions, scope, proof, metadata, publicKey.label, authentication)
    request.session.setAttribute(COMPLETION, Completion(scope, proof, checkNotNull(metadata.idempotencyKey), registrationFingerprint(publicKey), ceremony.challenge, result["passkeyId"] as? String))
    request.session.removeAttribute(REGISTRATION)
    // Additional registration extends a live Passkey session; it must not leave that
    // session restricted. Enrollment/recovery still require a separate Passkey login.
    if (scope.scope == RestrictedSessionScope.ADDITIONAL_PASSKEY) request.session.removeAttribute(RESTRICTED_SESSION)
    result
  }

  private fun complete(tx: TransactionPort, scope: RestrictedSession, proof: VerifiedPasskeyRegistration,
    metadata: CommandMetadata, label: String, authentication: Authentication?, previousPasskeyId: String? = null): Map<String, Any> = when (scope.scope) {
    RestrictedSessionScope.ENROLLMENT -> when (val result = CompletePasskeyEnrollment(tx, clock, random, hashes, roles)
      .execute(CompletePasskeyEnrollmentCommand(scope.id, proof, metadata, label))) {
      is CompletePasskeyEnrollmentResult.Completed -> mapOf("success" to true, "recoveryCode" to result.recoveryCode.format())
      is CompletePasskeyEnrollmentResult.AlreadyCompleted -> mapOf("success" to true)
      CompletePasskeyEnrollmentResult.IdempotencyConflict -> throw CeremonyConflict()
      else -> throw CeremonyRejected()
    }
    RestrictedSessionScope.RECOVERY -> when (val result = CompletePasskeyRecovery(tx, clock, random, hashes)
      .execute(CompletePasskeyRecoveryCommand(scope.id, proof, metadata, label))) {
      is CompletePasskeyRecoveryResult.Completed -> mapOf("success" to true, "recoveryCode" to result.recoveryCode.format())
      is CompletePasskeyRecoveryResult.AlreadyCompleted -> mapOf("success" to true)
      CompletePasskeyRecoveryResult.IdempotencyConflict -> throw CeremonyConflict()
      else -> throw CeremonyRejected()
    }
    RestrictedSessionScope.ADDITIONAL_PASSKEY -> {
      val principal = actors.sessionPrincipal(authentication) ?: throw CeremonyRejected()
      var passkeyId = previousPasskeyId
      // Capture the management identity under the same account lock and transaction as insertion.
      // Replay retains this metadata even if the credential has since been removed.
      val receiptTransactions = object : TransactionPort {
        override fun <T> execute(block: (TransactionContext) -> T): T = tx.execute { context ->
          block(context).also { result ->
            if ((result == SecurityChangeResult.Changed || result == SecurityChangeResult.Unchanged) && passkeyId == null) {
              val id = context.passkeyRegistrationReceipts.managementId(scope.userId, proof.credential.id) ?: throw CeremonyRejected()
              passkeyId = dev.moreal.finds.graphql.ManagementIds.encode("Passkey", id)
            }
          }
        }
      }
      val result = RegisterAdditionalPasskey(receiptTransactions, clock, random).execute(RegisterAdditionalPasskeyCommand(principal, scope.id, proof, label, metadata))
      if (result != SecurityChangeResult.Changed && result != SecurityChangeResult.Unchanged) throw CeremonyRejected()
      mapOf("success" to true, "passkeyId" to checkNotNull(passkeyId))
    }
  }

  private fun restricted(request: HttpServletRequest): RestrictedSession {
    val id = request.getSession(false)?.getAttribute(RESTRICTED_SESSION) as? RestrictedSessionId ?: throw CeremonyRejected()
    return transactions.execute { tx ->
      val session = tx.restrictedSessions.findById(id) ?: throw CeremonyRejected()
      val user = tx.users.findById(session.userId) ?: throw CeremonyRejected()
      if (!session.isUsable(clock.now()) || user.status == UserStatus.SUSPENDED ||
        (session.scope == RestrictedSessionScope.ENROLLMENT) != (user.status == UserStatus.PENDING_PASSKEY)) throw CeremonyRejected()
      session
    }
  }
  private fun snapshot(id: UserId) = transactions.execute { tx ->
    WebAuthnPersistenceAdapters(tx.users.findById(id) ?: throw CeremonyRejected(),
      tx.users.findUserHandle(id) ?: throw CeremonyRejected(), tx.credentials.findByUserId(id))
  }
  private fun issue(request: HttpServletRequest, purpose: WebAuthnChallengePurpose, bytes: Bytes, restricted: RestrictedSession?): WebAuthnChallenge {
    val id = random.uuid()
    val now = clock.now()
    val challenge = WebAuthnChallenge(id, purpose, settings.rpId,
      hashes.hash(IdentityHashPurpose.WEBAUTHN_CHALLENGE, id.toString(), bytes.toBase64UrlString()),
      hashes.hash(IdentityHashPurpose.WEBAUTHN_SESSION_BINDING, id.toString(), request.session.id),
      restricted?.userId, restricted?.id, now, now.plusSeconds(300))
    transactions.execute { tx ->
      restricted?.let { tx.users.lockByEmail(tx.users.findById(it.userId)?.email ?: throw CeremonyRejected()) }
      tx.webauthnChallenges.save(challenge)
    }
    return challenge
  }
  private fun checkBinding(request: HttpServletRequest, challenge: WebAuthnChallenge, bytes: Bytes) {
    val now = clock.now()
    if (now < challenge.createdAt || now >= challenge.expiresAt || challenge.rpId != settings.rpId ||
      !hashes.matches(challenge.hash, IdentityHashPurpose.WEBAUTHN_CHALLENGE, challenge.id.toString(), bytes.toBase64UrlString()) ||
      !hashes.matches(challenge.sessionBinding, IdentityHashPurpose.WEBAUTHN_SESSION_BINDING, challenge.id.toString(), request.session.id))
      throw CeremonyRejected()
  }
  private fun canonicalId(credential: PublicKeyCredential<*>) {
    if (credential.id != credential.rawId.toBase64UrlString() || credential.type != PublicKeyCredentialType.PUBLIC_KEY) throw CeremonyRejected()
  }
  private fun registrationFingerprint(key: RelyingPartyPublicKey) = CanonicalCommandEncoder.hash(mapOf(
    "id" to key.credential.id, "rawId" to key.credential.rawId.toBase64UrlString(), "type" to key.credential.type?.value,
    "attestation" to key.credential.response.attestationObject.toBase64UrlString(),
    "clientData" to key.credential.response.clientDataJSON.toBase64UrlString(), "label" to key.label,
    "transports" to key.credential.response.transports?.map { it.value }?.sorted(),
  ))
  private fun <T> verify(block: () -> T): T = try { block() } catch (_: Exception) { throw CeremonyRejected() }
  private class Ceremony<T>(val challenge: WebAuthnChallenge, val options: T)
  private class Completion(val scope: RestrictedSession, val proof: VerifiedPasskeyRegistration, val key: UUID,
    val fingerprint: dev.moreal.finds.application.port.CommandRequestHash, val challenge: WebAuthnChallenge, val passkeyId: String?)
  companion object {
    const val RESTRICTED_SESSION = "finds.restricted-session"
    private const val AUTHENTICATION = "finds.webauthn.authentication"
    private const val AUTHENTICATION_COMPLETED = "finds.webauthn.authentication-completed"
    private const val REGISTRATION = "finds.webauthn.registration"
    private const val COMPLETION = "finds.webauthn.completion"
  }
}
