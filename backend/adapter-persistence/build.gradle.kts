plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":application"))
  implementation(project(":domain"))
  implementation(libs.jooq)
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)
  runtimeOnly(libs.postgresql)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.postgresql)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.testcontainers.junit)
  testRuntimeOnly(libs.junit.platform.launcher)
}
