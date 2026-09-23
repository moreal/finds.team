plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":mail-core"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
