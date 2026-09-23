package dev.moreal.finds.domain.search

import dev.moreal.finds.domain.career.CareerSiteId
import dev.moreal.finds.domain.posting.JobPosting
import dev.moreal.finds.domain.posting.PostingStatus
import dev.moreal.finds.domain.posting.EmploymentType
import dev.moreal.finds.domain.posting.RemotePolicy
import dev.moreal.finds.domain.posting.RoleCategory
import dev.moreal.finds.domain.posting.SkillRequirementLevel
import dev.moreal.finds.domain.posting.SkillTaxonomy
import java.time.Instant
import java.util.Locale

sealed interface Filter {
  data class AtSite(val siteId: CareerSiteId) : Filter

  data class TextContains(val text: String) : Filter {
    init {
      require(text.isNotBlank()) { "Text filter must not be blank" }
    }
  }

  data class HasStatus(val status: PostingStatus) : Filter

  data class UpdatedAfter(val instant: Instant) : Filter

  data class HasSkill(val slug: String, val level: SkillRequirementLevel? = null) : Filter {
    init { SkillTaxonomy.V1.requireSkill(slug) }
  }
  data class HasRole(val role: RoleCategory) : Filter
  data class HasEmployment(val employment: EmploymentType) : Filter
  data class HasRemotePolicy(val policy: RemotePolicy) : Filter
  data class AtLocation(val searchValue: String) : Filter {
    init { require(searchValue.isNotBlank()) { "Location search value must not be blank" } }
  }

  data class Not(val inner: Filter) : Filter

  class And(all: List<Filter>) : Filter {
    val all: List<Filter> = all.toList()

    override fun equals(other: Any?): Boolean = other is And && all == other.all

    override fun hashCode(): Int = all.hashCode()

    override fun toString(): String = "And(all=$all)"
  }

  class Or(any: List<Filter>) : Filter {
    val any: List<Filter> = any.toList()

    override fun equals(other: Any?): Boolean = other is Or && any == other.any

    override fun hashCode(): Int = any.hashCode()

    override fun toString(): String = "Or(any=$any)"
  }
}

fun Filter.matches(posting: JobPosting): Boolean = when (this) {
  is Filter.AtSite -> posting.careerSiteId == siteId
  is Filter.TextContains -> {
    val query = text.trim().lowercase(Locale.ROOT)
    posting.raw.title.lowercase(Locale.ROOT).contains(query) ||
      posting.raw.descriptionText.lowercase(Locale.ROOT).contains(query)
  }
  is Filter.HasStatus -> posting.status == status
  is Filter.UpdatedAfter -> posting.updatedAt.isAfter(instant)
  is Filter.HasSkill -> posting.classification?.skills?.any { it.slug == slug && (level == null || it.level == level) } == true
  is Filter.HasRole -> posting.classification?.role?.value == role
  is Filter.HasEmployment -> posting.classification?.employment?.value == employment
  is Filter.HasRemotePolicy -> posting.classification?.remote?.value == policy
  is Filter.AtLocation -> posting.classification?.location?.searchValue == searchValue
  is Filter.Not -> !inner.matches(posting)
  is Filter.And -> all.all { it.matches(posting) }
  is Filter.Or -> any.any { it.matches(posting) }
}

fun Filter.normalize(): Filter = when (this) {
  is Filter.AtSite,
  is Filter.TextContains,
  is Filter.HasStatus,
  is Filter.UpdatedAfter,
  is Filter.HasSkill,
  is Filter.HasRole,
  is Filter.HasEmployment,
  is Filter.HasRemotePolicy,
  is Filter.AtLocation,
  -> this
  is Filter.Not -> {
    when (val normalizedInner = inner.normalize()) {
      is Filter.Not -> normalizedInner.inner.normalize()
      else -> Filter.Not(normalizedInner)
    }
  }
  is Filter.And -> Filter.And(
    all
      .map(Filter::normalize)
      .flatMap { child -> if (child is Filter.And) child.all else listOf(child) }
      .distinct(),
  )
  is Filter.Or -> Filter.Or(
    any
      .map(Filter::normalize)
      .flatMap { child -> if (child is Filter.Or) child.any else listOf(child) }
      .distinct(),
  )
}
