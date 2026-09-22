package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.model.Job
import org.springframework.stereotype.Service

@Service
class JobCrawlerService(private val crawlers: List<JobCrawler>) {
  fun crawlJobs(careerSite: CareerSite): List<Job> {
    val crawler = crawlers.find { crawler -> crawler.matches(careerSite) }
      ?: throw Exception("No crawler found")

    return crawler.crawlJobs(careerSite)
  }
}
