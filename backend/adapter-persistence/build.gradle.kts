plugins {
  alias(libs.plugins.kotlin.jvm)
}

val codegen = sourceSets.create("codegen")

dependencies {
  implementation(project(":application"))
  implementation(project(":domain"))
  implementation(libs.jooq)
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)
  runtimeOnly(libs.postgresql)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.postgresql)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.testcontainers.junit)
  testRuntimeOnly(libs.junit.platform.launcher)
  add(codegen.implementationConfigurationName, libs.jooq.codegen.lib)
  add(codegen.implementationConfigurationName, libs.flyway.core)
  add(codegen.implementationConfigurationName, libs.flyway.postgresql)
  add(codegen.implementationConfigurationName, libs.postgresql)
  add(codegen.implementationConfigurationName, libs.testcontainers.postgresql)
}

kotlin.sourceSets.named("main") {
  kotlin.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
}

val jooqCodegen = tasks.register<JavaExec>("jooqCodegen") {
  group = "jooq"
  description = "Migrates ephemeral PostgreSQL and generates jOOQ Kotlin sources"
  dependsOn(tasks.named(codegen.classesTaskName))
  classpath = codegen.runtimeClasspath
  mainClass = "dev.moreal.finds.persistence.codegen.PostgresCodegenKt"
  val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
  val output = layout.buildDirectory.dir("generated-src/jooq/main")
  inputs.files(fileTree(migrations) { include("*.sql") })
  outputs.dir(output)
  doFirst {
    args(migrations.asFile.absolutePath, output.get().asFile.absolutePath)
  }
}

tasks.named("compileKotlin") {
  dependsOn(jooqCodegen)
}
