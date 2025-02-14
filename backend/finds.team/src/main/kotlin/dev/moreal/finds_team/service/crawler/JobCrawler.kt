package dev.moreal.finds_team.service.crawler

import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.model.Job

interface JobCrawler {
  fun crawlJobs(careerSite: CareerSite): List<Job>;
  fun matches(careerSite: CareerSite): Boolean;
}
