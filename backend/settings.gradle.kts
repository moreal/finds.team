pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { mavenCentral() }
}

rootProject.name = "finds-team"
include(
  "domain",
  "application",
  "mail-core",
  "mail-transport-testing",
  "mail-transport-retry",
  "mail-transport-pool",
  "mail-observability",
  "mail-transport-smtp",
  "mail-transport-ses",
  "adapter-source",
  "adapter-persistence",
  "adapter-notification",
  "adapter-graphql",
  "bootstrap",
)
