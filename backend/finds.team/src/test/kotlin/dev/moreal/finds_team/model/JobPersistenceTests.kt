package dev.moreal.finds_team.model

import dev.moreal.finds_team.repository.CareerSiteRepository
import dev.moreal.finds_team.repository.JobRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals

@DataJpaTest
class JobPersistenceTests {
  @Autowired
  private lateinit var careerSiteRepository: CareerSiteRepository

  @Autowired
  private lateinit var jobRepository: JobRepository

  @Autowired
  private lateinit var entityManager: EntityManager

  @Test
  fun `stores descriptions longer than the default varchar length`() {
    val careerSite = careerSiteRepository.saveAndFlush(
      CareerSite(
        name = "Acme",
        url = "https://acme.career.greetinghr.com",
      ),
    )
    val description = "Long job description. ".repeat(100)
    val saved = jobRepository.saveAndFlush(
      Job(
        title = "Backend Engineer",
        description = description,
        url = "https://acme.career.greetinghr.com/o/101",
        careerSite = careerSite,
      ),
    )

    entityManager.clear()

    assertEquals(
      description,
      jobRepository.findById(saved.id).orElseThrow().description,
    )
  }
}
