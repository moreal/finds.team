package dev.moreal.finds_team.service

import dev.moreal.finds_team.model.Job
import dev.moreal.finds_team.repository.JobRepository
import org.springframework.stereotype.Service

@Service
class JobService(
  private val jobRepository: JobRepository,
) {
  fun getAllJobs(): List<Job> = jobRepository.findAll()

  fun createJob(job: Job): Job = jobRepository.save(job)
  fun createJobs(jobs: List<Job>): List<Job> = jobRepository.saveAll(jobs)
}
