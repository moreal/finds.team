package dev.moreal.finds_team.dto.response

data class JobDto(
  val id: Long,
  val title: String,
  val description: String,
  val url: String,
  val careerSiteId: Long,
)
