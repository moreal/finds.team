package dev.moreal.finds_team.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("finds")
data class FindsProperties(
  @field:Valid val source: Source = Source(),
  @field:Valid val crawl: Crawl = Crawl(),
) {
  data class Source(
    @field:NotBlank val userAgentProduct: String = "finds.team",
    @field:NotBlank val robotsProductToken: String = "findsteam",
    @field:NotBlank val contactUrl: String = "https://finds.team/contact",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(20),
    val siteTimeout: Duration = Duration.ofMinutes(2),
    val minimumHostSpacing: Duration = Duration.ofSeconds(1),
    @field:Min(1) val maximumResponseBytes: Long = 5L * 1024 * 1024,
    @field:Min(0) @field:Max(10) val maximumRedirects: Int = 5,
    val robotsSuccessTtl: Duration = Duration.ofHours(1),
    val robotsFailureTtl: Duration = Duration.ofMinutes(5),
    @field:Min(1) val sitemapMaximumDepth: Int = 4,
    @field:Min(1) val sitemapMaximumDocuments: Int = 100,
    @field:Min(1) val sitemapMaximumUrls: Int = 50_000,
    @field:Min(1) val sitemapMaximumTotalBytes: Long = 20L * 1024 * 1024,
  ) {
    init {
      requirePositive(connectTimeout, "connectTimeout")
      requirePositive(requestTimeout, "requestTimeout")
      requirePositive(siteTimeout, "siteTimeout")
      requireNonNegative(minimumHostSpacing, "minimumHostSpacing")
      requirePositive(robotsSuccessTtl, "robotsSuccessTtl")
      requirePositive(robotsFailureTtl, "robotsFailureTtl")
      val contact = runCatching { URI(contactUrl) }.getOrNull()
      require(contact?.scheme in setOf("http", "https") && !contact?.host.isNullOrBlank()) {
        "contactUrl must be an absolute HTTP(S) URL"
      }
    }
  }

  data class Crawl(
    val scanInterval: Duration = Duration.ofMinutes(1),
    val successInterval: Duration = Duration.ofHours(6),
    val retryDelays: List<Duration> = listOf(
      Duration.ofMinutes(30),
      Duration.ofHours(2),
      Duration.ofHours(6),
    ),
    val leaseDuration: Duration = Duration.ofMinutes(10),
    @field:NotBlank val leaseOwner: String = "local",
    @field:Min(1) val dispatchLimit: Int = 100,
    @field:Min(1) val globalConcurrency: Int = 4,
    @field:Min(1) val closeAfterMisses: Int = 2,
  ) {
    init {
      requirePositive(scanInterval, "scanInterval")
      requirePositive(successInterval, "successInterval")
      requirePositive(leaseDuration, "leaseDuration")
      require(retryDelays.isNotEmpty() && retryDelays.all { !it.isNegative && !it.isZero }) {
        "retryDelays must contain positive durations"
      }
      require(retryDelays.zipWithNext().all { (earlier, later) -> earlier <= later }) {
        "retryDelays must not decrease"
      }
    }
  }

  companion object {
    private fun requirePositive(value: Duration, name: String) {
      require(!value.isNegative && !value.isZero) { "$name must be positive" }
    }

    private fun requireNonNegative(value: Duration, name: String) {
      require(!value.isNegative) { "$name must not be negative" }
    }
  }
}
