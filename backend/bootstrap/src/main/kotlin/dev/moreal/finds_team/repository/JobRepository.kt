package dev.moreal.finds_team.repository

import dev.moreal.finds_team.model.Job
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JobRepository : JpaRepository<Job, Long>
