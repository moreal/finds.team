package dev.moreal.finds.persistence

import dev.moreal.finds.domain.posting.EmploymentType
import dev.moreal.finds.domain.posting.RemotePolicy
import dev.moreal.finds.domain.posting.RoleCategory
import dev.moreal.finds.domain.search.Filter
import kotlin.test.Test
import kotlin.test.assertFailsWith

class UnsupportedEnrichmentFilterTest {
  @Test
  fun `unpersisted enrichment filters fail explicitly even under Boolean nesting`() {
    listOf(Filter.HasSkill("kotlin"), Filter.HasRole(RoleCategory.BACKEND),
      Filter.HasEmployment(EmploymentType.FULL_TIME), Filter.HasRemotePolicy(RemotePolicy.REMOTE),
      Filter.AtLocation("seoul")).forEach { leaf ->
      listOf(leaf, Filter.Not(leaf), Filter.And(listOf(leaf)), Filter.Or(listOf(leaf))).forEach { filter ->
        assertFailsWith<UnsupportedOperationException> { filter.toCondition() }
      }
    }
  }
}
