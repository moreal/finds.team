package dev.moreal.finds_team.controller

import dev.moreal.finds_team.dto.response.JobDto
import dev.moreal.finds_team.model.Job
import dev.moreal.finds_team.service.JobService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.sql.SQLIntegrityConstraintViolationException

@RestController
@RequestMapping("/api/jobs")
class JobController(
  private val jobService: JobService,
) {
  @GetMapping
  fun getJobs(): List<JobDto> = jobService.getAllJobs().map { job ->
    JobDto(
      id = job.id,
      title = job.title,
      description = job.description,
      url = job.url,
      careerSiteId = job.careerSite.id,
    )
  }

  @PostMapping
  fun createJob(
    @RequestBody job: Job,
  ): ResponseEntity<JobDto> {
    return try {
      val jobEntity = jobService.createJob JobService.createJob(job)
      ResponseEntity.ok(
        JobDto(
          id = jobEntity.id,
          title = jobEntity.title,
          description = jobEntity.description,
          url = jobEntity.url,
          careerSiteId = jobEntity.careerSite.id,
        )
      )
    } catch (e: SQLIntegrityConstraintViolationException) {
      ResponseEntity.status(HttpStatus.CONFLICT).build()
    }
  }
}
