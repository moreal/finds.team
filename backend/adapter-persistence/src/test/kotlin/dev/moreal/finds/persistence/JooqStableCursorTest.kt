package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.persistence.jooq.generated.tables.references.*
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DSL

class JooqStableCursorTest : PostgresIntegrationTest() {
  @Test
  fun `equal timestamps use ID tie breaker and newer inserts do not duplicate or skip following rows`() {
    val (source, db) = migratedContext()
    val site = site(db)
    val ids = (1..5).map { posting(db, site, "p$it") }
    posting(db, site, "closed", open = false)
    val query = JooqDiscoveryQuery(db)
    val filter = Filter.HasStatus(PostingStatus.OPEN)
    val first = query.postings(filter, ConnectionRequest(2))
    assertEquals(ids.takeLast(2).reversed(), first.edges.map { it.node.id })
    assertEquals(5, first.totalCount)
    assertPage(first, next = true, previous = false)
    // A distinct connection commits a newer posting between page requests.
    source.connection.use { connection ->
      posting(DSL.using(connection, org.jooq.SQLDialect.POSTGRES), site, "new", time = NOW.plusSeconds(1))
    }
    val middle = query.postings(filter, ConnectionRequest(2, first.pageInfo.endCursor))
    assertEquals(ids.slice(1..2).reversed(), middle.edges.map { it.node.id })
    assertEquals(6, middle.totalCount)
    assertPage(middle, next = true, previous = true)
    val last = query.postings(filter, ConnectionRequest(2, middle.pageInfo.endCursor))
    assertEquals(listOf(ids.first()), last.edges.map { it.node.id })
    assertPage(last, next = false, previous = true)
    assertEquals(ids.reversed(), (first.edges + middle.edges + last.edges).map { it.node.id })
    val empty = query.postings(filter, ConnectionRequest(2, last.pageInfo.endCursor))
    assertEquals(6, empty.totalCount)
    assertTrue(empty.edges.isEmpty())
    assertPage(empty, next = false, previous = true)
    val noMatches = query.postings(Filter.TextContains("absent"), ConnectionRequest(1))
    assertEquals(0, noMatches.totalCount)
    assertPage(noMatches, next = false, previous = false)
  }

  @Test
  fun `posting detail and site slug survive detail lookups and unknown IDs return null`() {
    val (_, db) = migratedContext()
    val site = site(db)
    val id = posting(db, site, "detail", skills = listOf("kotlin"))
    val queries = JooqDiscoveryQuery(db)
    val detail = GetJobPosting(queries).execute(id)!!
    assertEquals("detail", detail.raw.externalKey)
    assertEquals(listOf("kotlin"), detail.classification!!.skills.map { it.slug })
    assertEquals(null, GetJobPosting(queries).execute(JobPostingId(Long.MAX_VALUE)))
    val slug = JooqCareerSiteRepository(db).findById(site)!!.slug!!
    assertEquals(site, GetCareerSite(queries).execute(slug)!!.id)
    assertEquals(null, GetCareerSite(queries).execute("missing"))
  }

  @Test
  fun `company and skill relationships are bounded distinct open filtered connections`() {
    val (_, db) = migratedContext()
    val a = site(db, "a")
    val b = site(db, "b")
    val c = site(db, "c")
    posting(db, a, "a1", skills = listOf("kotlin", "java", "spring"))
    posting(db, a, "a2", skills = listOf("kotlin", "java"), level = SkillRequirementLevel.PREFERRED)
    posting(db, b, "b1", skills = listOf("kotlin", "rust"))
    posting(db, c, "c1", open = false, skills = listOf("kotlin", "python"))
    val query = JooqDiscoveryQuery(db)
    val companies = query.skillCompanies("kotlin", ConnectionRequest(1))
    assertEquals(listOf(a), companies.edges.map { it.node.id })
    assertEquals(2, companies.totalCount)
    assertPage(companies, next = true, previous = false)
    val companyTail = query.skillCompanies("kotlin", ConnectionRequest(1, companies.pageInfo.endCursor))
    assertEquals(listOf(b), companyTail.edges.map { it.node.id })
    assertPage(companyTail, next = false, previous = true)
    val related = query.relatedSkills("kotlin", ConnectionRequest(2))
    assertEquals(listOf("java", "rust"), related.edges.map { it.node.slug })
    assertEquals(3, related.totalCount)
    assertPage(related, next = true, previous = false)
    val relatedTail = query.relatedSkills("kotlin", ConnectionRequest(2, related.pageInfo.endCursor))
    assertEquals(listOf("spring"), relatedTail.edges.map { it.node.slug })
    assertPage(relatedTail, next = false, previous = true)
    assertEquals(2, GetCareerSite(query).openPostings(a, ConnectionRequest(1)).totalCount)
    assertEquals(0, GetCareerSite(query).openPostings(c, ConnectionRequest(1)).totalCount)
    assertEquals(3, GetSkill(query).postings("kotlin", ConnectionRequest(1)).totalCount)
    assertEquals(SkillRequirementCounts(2, 1, 0), query.skillRequirementCounts("kotlin"))
    assertEquals("Kotlin", GetSkill(query).execute("kotlin").displayName)
    assertFailsWith<UnknownSkillException> { GetSkill(query).execute("Kotlin") }
    assertFailsWith<UnknownSkillException> { query.skillCompanies("invented", ConnectionRequest()) }
    assertFailsWith<UnknownSkillException> { query.relatedSkills("invented", ConnectionRequest()) }
  }

  @Test
  fun `catalog and company pages have complete metadata and bind cursors to query and collection`() {
    val (_, db) = migratedContext()
    val a = site(db, "same-a")
    val b = site(db, "same-b")
    val query = JooqDiscoveryQuery(db)
    val sites = query.careerSites(ConnectionRequest(1))
    assertEquals(listOf(a), sites.edges.map { it.node.id })
    assertEquals(2, sites.totalCount)
    assertPage(sites, next = true, previous = false)
    val tail = query.careerSites(ConnectionRequest(1, sites.pageInfo.endCursor))
    assertEquals(listOf(b), tail.edges.map { it.node.id })
    assertPage(tail, next = false, previous = true)
    assertPage(query.careerSites(ConnectionRequest(1, tail.pageInfo.endCursor)), next = false, previous = true)
    val skills = query.skills(null, ConnectionRequest(1))
    assertEquals("airflow", skills.edges.single().node.slug)
    assertPage(skills, next = true, previous = false)
    val more = query.skills(null, ConnectionRequest(1, skills.pageInfo.endCursor))
    assertEquals("android", more.edges.single().node.slug)
    assertPage(more, next = true, previous = true)
    assertEquals(listOf("kotlin"), query.skills("코틀린", ConnectionRequest()).edges.map { it.node.slug })
    assertFailsWith<InvalidConnectionCursor> { query.skills("rust", ConnectionRequest(1, skills.pageInfo.endCursor)) }
    assertFailsWith<InvalidConnectionCursor> { query.skillCompanies("kotlin", ConnectionRequest(1, sites.pageInfo.endCursor)) }
  }

  @Test
  fun `cursor is bound to full normalized filter even when caller changes page size`() {
    val (_, db) = migratedContext()
    val site = site(db)
    repeat(3) { posting(db, site, "k$it", skills = listOf("kotlin")) }
    val query = JooqDiscoveryQuery(db)
    val filter = Filter.HasSkill("kotlin")
    val first = query.postings(filter, ConnectionRequest(1))
    assertEquals(2, query.postings(filter, ConnectionRequest(2, first.pageInfo.endCursor)).edges.size)
    assertFailsWith<InvalidConnectionCursor> {
      query.postings(Filter.HasSkill("rust"), ConnectionRequest(1, first.pageInfo.endCursor))
    }
    assertFailsWith<InvalidConnectionCursor> {
      query.postings(filter, ConnectionRequest(1, ApplicationCursor("malformed")))
    }
  }

  @Test
  fun `posting page count skills and pageInfo share a snapshot with one SQL query`() {
    val (source, db) = migratedContext()
    val site = site(db)
    repeat(4) { posting(db, site, "k$it", skills = listOf("kotlin")) }
    val statements = AtomicInteger()
    var interleaved = false
    val intercepted = DSL.using(db.configuration().derive(object : ExecuteListener {
      override fun executeStart(ctx: ExecuteContext) {
        if (ctx.sql()?.startsWith("select", true) == true) statements.incrementAndGet()
      }
      override fun fetchEnd(ctx: ExecuteContext) {
        if (!interleaved && ctx.sql()?.contains("content_hash") == true) {
          interleaved = true
          source.connection.use { connection ->
            val other = DSL.using(connection, org.jooq.SQLDialect.POSTGRES)
            other.transaction { config ->
              val tx = DSL.using(config)
              tx.update(JOB_POSTINGS).set(JOB_POSTINGS.TITLE, "Changed").execute()
              tx.deleteFrom(POSTING_SKILLS).execute()
            }
          }
        }
      }
    }))
    val page = JooqDiscoveryQuery(intercepted).postings(Filter.HasSkill("kotlin"), ConnectionRequest(3))
    assertTrue(interleaved)
    assertEquals(4, page.totalCount)
    assertEquals(3, page.edges.size)
    assertTrue(page.edges.all { it.node.raw.title != "Changed" && it.node.classification!!.skills.single().slug == "kotlin" })
    assertPage(page, next = true, previous = false)
    assertEquals(1, statements.get(), "Count, page info, postings and skill associations must use one SQL snapshot")
    assertEquals(0, JooqDiscoveryQuery(db).postings(Filter.HasSkill("kotlin"), ConnectionRequest()).totalCount)
  }

  @Test
  fun `skill relationship counts and edges share one snapshot and fixed query budget`() {
    listOf(false, true).forEach { readRelated ->
      val (source, db) = migratedContext()
      val a = site(db, "a")
      val b = site(db, "b")
      posting(db, a, "a", skills = listOf("kotlin", "java"))
      posting(db, b, "b", skills = listOf("kotlin", "rust"))
      val selects = AtomicInteger()
      var interleaved = false
      val intercepted = DSL.using(db.configuration().derive(object : ExecuteListener {
        override fun executeStart(ctx: ExecuteContext) {
          if (ctx.sql()?.startsWith("select", true) == true) selects.incrementAndGet()
        }
        override fun fetchEnd(ctx: ExecuteContext) {
          if (!interleaved && ctx.sql()?.startsWith("select", true) == true) {
            interleaved = true
            source.connection.use { connection ->
              DSL.using(connection, org.jooq.SQLDialect.POSTGRES).deleteFrom(POSTING_SKILLS).execute()
            }
          }
        }
      }))
      val query = JooqDiscoveryQuery(intercepted)
      val page = if (readRelated) query.relatedSkills("kotlin", ConnectionRequest(1))
        else query.skillCompanies("kotlin", ConnectionRequest(1))
      assertTrue(interleaved)
      assertEquals(2, page.totalCount)
      assertEquals(1, page.edges.size)
      assertPage(page, next = true, previous = false)
      assertEquals(1, selects.get(), "A relationship page must not load rows/counts separately")
      val fresh = JooqDiscoveryQuery(db)
      assertEquals(0, fresh.skillCompanies("kotlin", ConnectionRequest()).totalCount)
      assertEquals(0, fresh.relatedSkills("kotlin", ConnectionRequest()).totalCount)
    }
  }

  @Test
  fun `invalid cursor order fields are typed and deleted cursor boundary does not imply a previous page`() {
    val (_, db) = migratedContext()
    val site = site(db)
    val query = JooqDiscoveryQuery(db)
    val scope = ConnectionCursors.scope("sites", "id-asc")
    listOf("-1", "0", "01", "9223372036854775808", "nan").forEach { invalid ->
      assertFailsWith<InvalidConnectionCursor> {
        query.careerSites(ConnectionRequest(1, ConnectionCursors.encode(scope, listOf(invalid))))
      }
    }
    val first = query.careerSites(ConnectionRequest(1))
    db.deleteFrom(CAREER_SITES).where(CAREER_SITES.ID.eq(site.value)).execute()
    val empty = query.careerSites(ConnectionRequest(1, first.pageInfo.endCursor))
    assertEquals(0, empty.totalCount)
    assertPage(empty, next = false, previous = false)
  }

  private fun assertPage(page: ConnectionPage<*>, next: Boolean, previous: Boolean) {
    assertEquals(next, page.pageInfo.hasNextPage)
    assertEquals(previous, page.pageInfo.hasPreviousPage)
    assertEquals(page.edges.firstOrNull()?.cursor, page.pageInfo.startCursor)
    assertEquals(page.edges.lastOrNull()?.cursor, page.pageInfo.endCursor)
  }

  private fun site(db: DSLContext, name: String = "jobs"): CareerSiteId =
    assertIs<InsertCareerSiteResult.Inserted>(JooqCareerSiteRepository(db).insert(NewCareerSite(
      assertIs<SiteUrlResult.Valid>(SiteUrl.parse("https://$name.example")).url,
      SourceProvider.FLEX, "Company",
    ))).site.id

  private fun posting(db: DSLContext, site: CareerSiteId, key: String, open: Boolean = true,
    time: Instant = NOW, skills: List<String> = emptyList(), level: SkillRequirementLevel = SkillRequirementLevel.REQUIRED): JobPostingId {
    val id = db.insertInto(JOB_POSTINGS)
      .set(JOB_POSTINGS.CAREER_SITE_ID, site.value).set(JOB_POSTINGS.EXTERNAL_KEY, key)
      .set(JOB_POSTINGS.TITLE, key).set(JOB_POSTINGS.DESCRIPTION_TEXT, "Kotlin required")
      .set(JOB_POSTINGS.CANONICAL_URL, "https://jobs.example/$key").set(JOB_POSTINGS.CONTENT_HASH, "a".repeat(64))
      .set(JOB_POSTINGS.STATUS, if (open) "OPEN" else "CLOSED")
      .set(JOB_POSTINGS.FIRST_SEEN_AT, NOW.minusSeconds(100).atOffset(UTC))
      .set(JOB_POSTINGS.LAST_SEEN_AT, time.atOffset(UTC)).set(JOB_POSTINGS.UPDATED_AT, time.atOffset(UTC))
      .set(JOB_POSTINGS.CLOSED_AT, if (open) null else time.atOffset(UTC))
      .set(JOB_POSTINGS.TAXONOMY_VERSION, 1).set(JOB_POSTINGS.ROLE_CATEGORY, "BACKEND")
      .set(JOB_POSTINGS.EMPLOYMENT_TYPE, "FULL_TIME").set(JOB_POSTINGS.REMOTE_POLICY, "REMOTE")
      .returning(JOB_POSTINGS.ID).fetchSingle().id!!
    skills.forEachIndexed { order, slug ->
      db.insertInto(POSTING_SKILLS).set(POSTING_SKILLS.JOB_POSTING_ID, id).set(POSTING_SKILLS.SKILL, slug)
        .set(POSTING_SKILLS.CANONICAL_SLUG, slug).set(POSTING_SKILLS.MENTION_TEXT, slug)
        .set(POSTING_SKILLS.MENTION_ORDER, order).set(POSTING_SKILLS.REQUIREMENT_LEVEL, level.name).execute()
    }
    return JobPostingId(id)
  }

  companion object { private val NOW = Instant.parse("2026-09-22T00:00:00.123456Z") }
}
