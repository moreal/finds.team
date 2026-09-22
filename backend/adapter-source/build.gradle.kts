plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":application"))
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.jsoup)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
