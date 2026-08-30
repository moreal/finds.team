package dev.moreal.finds_team.model

import jakarta.persistence.*

@Entity
@Table(name = "career_sites")
data class CareerSite(
  @Id() @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
  @Column(nullable = false) val name: String,
  @Column(nullable = false, unique = true) val url: String,
  @Column(nullable = false) val siteTemplate: SiteTemplate = SiteTemplate.Unknown,
  @OneToMany(mappedBy = "careerSite", cascade = [CascadeType.ALL])
  val jobs: MutableList<Job> = mutableListOf(),
)
