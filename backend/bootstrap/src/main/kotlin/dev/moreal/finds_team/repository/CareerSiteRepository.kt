package dev.moreal.finds_team.repository

import dev.moreal.finds_team.model.CareerSite
import org.springframework.data.jpa.repository.JpaRepository

interface CareerSiteRepository : JpaRepository<CareerSite, Long>
