package dev.moreal.finds_team.controller

import dev.moreal.finds_team.dto.response.CareerSiteDto
import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.service.crawler.JobCrawlerService
import dev.moreal.finds_team.service.CareerSiteService
import dev.moreal.finds_team.service.JobService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.sql.SQLIntegrityConstraintViolationException

@RestController
@RequestMapping("/api/career-sites")
class CareerSiteController(
  private val careerSiteService: CareerSiteService,
  private val crawlerService: JobCrawlerService,
  private val jobService: JobService,
) {
  @GetMapping
  fun getCareerSites(): List<CareerSiteDto> =
    careerSiteService.getAllCareerSites().map { careerSite ->
      CareerSiteDto(
        id = careerSite.id,
        name = careerSite.name,
        url = careerSite.url,
      )
    }

  @PostMapping
  fun createCareerSite(
    @RequestBody careerSite: CareerSite,
  ): ResponseEntity<CareerSite> {
    return try {
      val result = careerSiteService.createCareerSite(careerSite)
      val jobs = crawlerService.crawlJobs(careerSite)
      jobService.createJobs(jobs)

      result.jobs.addAll(jobs)
      ResponseEntity.ok(result)
    } catch (e: SQLIntegrityConstraintViolationException) {
      when (e.errorCode) {
        1062 -> ResponseEntity.status(HttpStatus.CONFLICT)
        else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      }.build()
    }
  }
}
