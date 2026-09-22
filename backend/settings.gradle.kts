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
  "adapter-source",
  "adapter-persistence",
  "adapter-graphql",
  "bootstrap",
)
