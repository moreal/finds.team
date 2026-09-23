plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":domain"))
  implementation(project(":application"))
  implementation(project(":mail-core"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(project(":adapter-persistence"))
  testImplementation(project(":mail-transport-testing"))
  testImplementation(project(":mail-transport-retry"))
  testImplementation(project(":mail-transport-pool"))
  testImplementation(project(":mail-transport-smtp"))
  testImplementation(testFixtures(project(":application")))
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testRuntimeOnly(libs.junit.platform.launcher)
}
