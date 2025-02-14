package dev.moreal.finds_team.model

import jakarta.persistence.*

@Entity
@Table(name = "jobs")
data class Job(
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
  @Column(nullable = false) val title: String,
  @Column(nullable = false) val description: String,
  @Column(nullable = false, unique = true) val url: String,
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "jobs_id")
  val careerSite: CareerSite,
)
