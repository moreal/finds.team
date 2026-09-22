plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":domain"))
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}
