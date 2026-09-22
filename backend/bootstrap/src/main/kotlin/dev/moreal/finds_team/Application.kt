package dev.moreal.finds_team

import dev.moreal.finds_team.config.FindsProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FindsProperties::class)
class Application

fun main(args: Array<String>) {
  runApplication<Application>(*args)
}
