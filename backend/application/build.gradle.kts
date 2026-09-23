plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

dependencies {
  implementation(project(":domain"))
  api(project(":mail-core"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testRuntimeOnly(libs.junit.platform.launcher)
  testFixturesImplementation(project(":domain"))
  testFixturesImplementation(libs.kotlin.test.junit5)
  testFixturesImplementation(libs.kotlinx.coroutines.test)
}
