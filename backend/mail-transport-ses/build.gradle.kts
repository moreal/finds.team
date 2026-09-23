plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":mail-core"))
  implementation(libs.aws.ses)
  implementation(libs.angus.mail)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}
