plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.spring)
  alias(libs.plugins.kotlin.jpa)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
}

dependencies {
  implementation(project(":domain"))
  implementation(project(":application"))
  implementation(project(":adapter-source"))
  implementation(project(":adapter-persistence"))
  implementation(project(":adapter-graphql"))
  implementation(libs.spring.boot.starter)
  implementation(libs.spring.boot.starter.web)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.kotlin.reflect)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.jsoup)
  runtimeOnly(libs.h2)
  testImplementation(libs.spring.boot.starter.test)
  testImplementation(libs.spring.boot.data.jpa.test)
  testImplementation(libs.kotlin.test.junit5)
  testRuntimeOnly(libs.junit.platform.launcher)
}
