plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":application"))
  implementation(project(":domain"))
  implementation(libs.graphql.java)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.coroutines.test)
  testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets.main {
  resources.srcDir("../../schema")
}
