package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface HostResolver {
  suspend fun resolve(host: SiteHost): List<InetAddress>
}

class JvmHostResolver : HostResolver {
  override suspend fun resolve(host: SiteHost): List<InetAddress> =
    withContext(Dispatchers.IO) {
      InetAddress.getAllByName(host.value).toList()
    }
}

sealed interface DestinationDecision {
  data object Allowed : DestinationDecision

  data class Rejected(val failure: WebFailure) : DestinationDecision
}

class DestinationPolicy(
  private val resolver: HostResolver,
) {
  suspend fun evaluate(
    url: SiteUrl,
    allowedHosts: Set<SiteHost>,
  ): DestinationDecision {
    if (url.host !in allowedHosts) {
      return rejected(
        WebFailureCode.INVALID_DESTINATION,
        "Destination host ${url.host.value} is not allowed",
      )
    }

    val addresses = try {
      resolver.resolve(url.host)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      return rejected(
        WebFailureCode.DNS_REJECTED,
        "DNS resolution failed for ${url.host.value}: ${error.safeMessage()}",
      )
    }
    if (addresses.isEmpty()) {
      return rejected(
        WebFailureCode.DNS_REJECTED,
        "DNS resolution returned no addresses for ${url.host.value}",
      )
    }
    if (addresses.any { !it.isGloballyRoutable() }) {
      return rejected(
        WebFailureCode.DNS_REJECTED,
        "DNS resolution for ${url.host.value} included a non-public address",
      )
    }
    return DestinationDecision.Allowed
  }

  private fun rejected(code: WebFailureCode, message: String) =
    DestinationDecision.Rejected(
      WebFailure(
        code,
        message.replace(Regex("[\\r\\n]+"), " ").trim().take(1_000),
      ),
    )
}

private fun InetAddress.isGloballyRoutable(): Boolean = when (this) {
  is Inet4Address -> address.isPublicIpv4()
  is Inet6Address -> address.isPublicIpv6()
  else -> false
}

private fun ByteArray.isPublicIpv4(): Boolean {
  if (size != 4) return false
  val first = this[0].toInt() and 0xff
  val second = this[1].toInt() and 0xff
  val third = this[2].toInt() and 0xff
  return when {
    first == 0 || first == 10 || first == 127 -> false
    first == 100 && second in 64..127 -> false
    first == 169 && second == 254 -> false
    first == 172 && second in 16..31 -> false
    first == 192 && second == 0 && third in setOf(0, 2) -> false
    first == 192 && second == 168 -> false
    first == 198 && second in 18..19 -> false
    first == 198 && second == 51 && third == 100 -> false
    first == 203 && second == 0 && third == 113 -> false
    first >= 224 -> false
    else -> true
  }
}

private fun ByteArray.isPublicIpv6(): Boolean {
  if (size != 16) return false
  if (all { it == 0.toByte() }) return false
  if (dropLast(1).all { it == 0.toByte() } && last() == 1.toByte()) return false
  val first = this[0].toInt() and 0xff
  val second = this[1].toInt() and 0xff
  if (first == 0xff) return false
  if (first in 0xfc..0xfd) return false
  if (first == 0xfe && second in 0x80..0xbf) return false
  if (
    sliceArray(0 until 10).all { it == 0.toByte() } &&
    this[10] == 0xff.toByte() && this[11] == 0xff.toByte()
  ) {
    return sliceArray(12 until 16).isPublicIpv4()
  }
  if (
    first == 0x20 && second == 0x01 &&
    (this[2].toInt() and 0xff) == 0x0d && (this[3].toInt() and 0xff) == 0xb8
  ) {
    return false
  }
  return true
}

internal fun Throwable.safeMessage(): String =
  (message ?: this::class.simpleName ?: "Transport failure")
    .replace(Regex("[\\r\\n]+"), " ")
    .trim()
    .take(1_000)
