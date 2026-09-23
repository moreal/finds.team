package dev.moreal.finds_team.config

import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class MigrationDataSourceConfiguration {
  @Bean(initMethod = "migrate")
  fun flyway(environment: Environment): Flyway {
    val runtimeUser = environment.getRequiredProperty("spring.datasource.username")
    val migrationUser = environment.getRequiredProperty("spring.flyway.user")
    if (environment.activeProfiles.any { it == "prod" || it == "production" }) {
      require(runtimeUser != migrationUser) {
        "Production requires distinct migration and runtime database usernames"
      }
    }
    return Flyway.configure()
      .dataSource(
        environment.getRequiredProperty("spring.flyway.url"),
        migrationUser,
        environment.getRequiredProperty("spring.flyway.password"),
      )
      .locations("classpath:db/migration")
      .load()
  }
}
