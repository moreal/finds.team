package dev.moreal.finds_team.service

import dev.moreal.finds_team.model.CareerSite
import dev.moreal.finds_team.repository.CareerSiteRepository
import org.springframework.stereotype.Service

@Service
class CareerSiteService(
  private val careerSiteRepository: CareerSiteRepository,
) {
  fun getAllCareerSites(): List<CareerSite> = careerSiteRepository.findAll()

  fun createCareerSite(careerSite: CareerSite): CareerSite =
    careerSiteRepository.save(careerSite)
}
