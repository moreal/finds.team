plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-test-fixtures`
}

dependencies {
  implementation(project(":application"))
  implementation(project(":domain"))
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.jsoup)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testRuntimeOnly(libs.junit.platform.launcher)
  testFixturesImplementation(project(":application"))
  testFixturesImplementation(project(":domain"))
  testFixturesImplementation(libs.kotlinx.coroutines.core)
}
