import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.spring) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spring.boot) apply false
  alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
  group = "dev.moreal"
  version = "0.1.0-SNAPSHOT"
}

subprojects {
  tasks.withType<Test>().configureEach { useJUnitPlatform() }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(25)
      compilerOptions.freeCompilerArgs.add("-Xjsr305=strict")
    }
  }
}
