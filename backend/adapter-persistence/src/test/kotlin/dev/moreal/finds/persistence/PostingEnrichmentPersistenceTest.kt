package dev.moreal.finds.persistence

import dev.moreal.finds.application.model.NewCareerSite
import dev.moreal.finds.application.model.PageRequest
import dev.moreal.finds.application.port.CrawlLease
import dev.moreal.finds.application.port.InsertCareerSiteResult
import dev.moreal.finds.domain.career.*
import dev.moreal.finds.domain.crawl.*
import dev.moreal.finds.domain.posting.*
import dev.moreal.finds.domain.search.Filter
import dev.moreal.finds.domain.search.matches
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.*
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

class PostingEnrichmentPersistenceTest : PostgresIntegrationTest() {
  @Test
  fun `V8 backfill preserves legacy skills and gives deterministic slugs regardless of physical row order`() {
    val expected = mapOf(10L to "acme-10", 20L to "acme-20", 30L to "company-30",
      40L to "caf-labs-40", 50L to "acme-10-50")
    for (order in listOf(expected.keys.toList(), expected.keys.reversed())) {
      val source = resetPublicSchema()
      Flyway.configure().dataSource(source).target("7").load().migrate()
      val db = DSL.using(source, SQLDialect.POSTGRES)
      val names = mapOf(10L to "Acme", 20L to "ACME!", 30L to "한글회사", 40L to "Café Labs", 50L to "Acme-10")
      order.forEach { id ->
        db.execute("INSERT INTO career_sites(id, canonical_base_url, host, provider, display_name) VALUES (?, ?, ?, 'FLEX', ?)",
          id, "https://site$id.example", "site$id.example", names.getValue(id))
      }
      legacyPosting(db, 10L, "old")
      val id = db.fetchValue("SELECT id FROM job_postings", Long::class.java)!!
      listOf("Kotlin", "코틀린", "unknown-tool").forEach {
        db.execute("INSERT INTO posting_skills(job_posting_id, skill) VALUES (?, ?)", id, it)
      }
      Flyway.configure().dataSource(source).load().migrate()

      assertTrue(db.fetchExists(DSL.selectOne().from("information_schema.columns")
        .where("table_name = 'career_sites' AND column_name = 'slug'")), "V8 must add stored slugs")
      assertEquals(expected, db.fetch("SELECT id, slug FROM career_sites").associate {
        it.get("id", Long::class.java) to it.get("slug", String::class.java)
      })
      assertEquals(listOf("MENTIONED", "MENTIONED", "MENTIONED"),
        db.fetch("SELECT requirement_level FROM posting_skills").map { it.get(0, String::class.java) })
      assertEquals(setOf("Kotlin", "코틀린", "unknown-tool"),
        db.fetch("SELECT skill FROM posting_skills").map { it.get(0, String::class.java) }.toSet())
      val stored = JooqPostingRepository(db).findByCareerSite(CareerSiteId(10)).single()
      assertEquals(SkillRequirementLevel.MENTIONED, stored.classification!!.skills.single { it.slug == "kotlin" }.level)
      assertEquals(1, JooqPostingRepository(db).search(Filter.HasSkill("kotlin"), PageRequest(10)).totalCount)
      assertEquals("Legacy unknown", stored.raw.employmentHint)
      assertEquals(EmploymentType.UNKNOWN, stored.classification!!.employment.value)
    }
  }

  @Test
  fun `registration produces unique stable stored slugs under concurrency and transaction rollback`() {
    val (source, db) = migratedContext()
    assertTrue(db.fetchExists(DSL.selectOne().from("information_schema.columns")
      .where("table_name = 'career_sites' AND column_name = 'slug'")), "registration requires stored slugs")
    val pool = Executors.newFixedThreadPool(4)
    val ids = try {
      (1..8).map { index -> pool.submit<Long> {
        val context = DSL.using(source, SQLDialect.POSTGRES)
        insertSite(context, "same$index.example", "Same Company").value
      } }.map { it.get(10, TimeUnit.SECONDS) }
    } finally { pool.shutdownNow() }
    val slugs = db.fetch("SELECT id, slug FROM career_sites").associate {
      it.get("id", Long::class.java) to it.get("slug", String::class.java)
    }
    assertEquals(ids.associateWith { "same-company-$it" }, slugs)
    ids.forEach { assertEquals(slugs[it], JooqCareerSiteRepository(db).findById(CareerSiteId(it))!!.slug) }
    db.execute("UPDATE career_sites SET display_name = 'Changed'")
    assertEquals(slugs.values.toSet(), db.fetch("SELECT slug FROM career_sites").map { it.get(0, String::class.java) }.toSet())
    assertFailsWith<DataAccessException> { db.execute("UPDATE career_sites SET slug = 'changed-1' WHERE id = ?", ids.first()) }
    assertFailsWith<IllegalStateException> {
      JooqTransactionAdapter(db) { null }.execute { tx ->
        tx.careerSites.insert(NewCareerSite(siteUrl("https://rollback.example"), SourceProvider.FLEX, "Same Company"))
        error("abort registration")
      }
    }
    assertEquals(8, db.fetchCount(DSL.table("career_sites")))
  }

  @Test
  fun `unchanged observations upgrade legacy classification without changing content timestamps`() {
    val (_, db) = migratedContext()
    val site = insertSite(db)
    legacyPosting(db, site.value, "old")
    val raw = JooqPostingRepository(db).findByCareerSite(site).single().raw.copy(title = "Backend", descriptionText = "Kotlin required")
    db.execute("UPDATE job_postings SET title = ?, description_text = ?, content_hash = ?", raw.title, raw.descriptionText, raw.contentHash())
    assertNull(JooqPostingRepository(db).findByCareerSite(site).single().classification)
    crawl(db, site, listOf(raw), 1)
    val stored = JooqPostingRepository(db).findByCareerSite(site).single()
    assertEquals(classifyPosting(raw), stored.classification)
    assertEquals(NOW, stored.updatedAt)
    assertEquals(NOW.plusSeconds(1), stored.lastSeenAt)
  }

  @Test
  fun `runtime role can register enrich and query without broader schema or audit privileges`() {
    val source = resetPublicSchema()
    val owner = DSL.using(source, SQLDialect.POSTGRES)
    owner.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN CREATE ROLE finds_app LOGIN PASSWORD 'task4-runtime'; END IF; END $$")
    Flyway.configure().dataSource(source).load().migrate()
    source.connection.use { connection ->
      connection.createStatement().use { it.execute("SET ROLE finds_app") }
      val db = DSL.using(connection, SQLDialect.POSTGRES)
      val site = insertSite(db)
      crawl(db, site, listOf(raw("a", "Backend", "Kotlin required")), 0)
      assertEquals("acme-${site.value}", JooqCareerSiteRepository(db).findById(site)!!.slug)
      assertEquals(1, JooqPostingRepository(db).search(Filter.HasSkill("kotlin"), PageRequest(10)).totalCount)
      assertFailsWith<DataAccessException> { db.execute("CREATE TABLE forbidden (id int)") }
      assertFailsWith<DataAccessException> { db.execute("DELETE FROM audit_events") }
    }
  }

  @Test
  fun `successful crawl persists classification and replaces skills atomically across touch update close and reopen`() {
    val (_, db) = migratedContext()
    val site = insertSite(db)
    val original = raw("a", "Backend Developer", "Requirements:\nKotlin required\nUnobtainium\nPreferred:\nJava",
      "정규직", " 서울 ", "hybrid")
    crawl(db, site, listOf(original), 0)
    val repository = JooqPostingRepository(db)
    val first = repository.findByCareerSite(site).single()
    assertNotNull(first.classification, "successful crawl must persist enrichment")
    assertEquals(classifyPosting(original), first.classification)
    assertEquals(RoleCategory.BACKEND, first.classification!!.role.value)
    assertEquals("seoul", first.classification!!.location!!.searchValue)
    assertEquals(SkillRequirementLevel.REQUIRED, first.classification!!.skills.single { it.slug == "kotlin" }.level)
    assertTrue(first.classification!!.skills.any { it.slug == null && it.text == "Unobtainium" })
    assertFailsWith<DataAccessException> {
      db.execute("INSERT INTO posting_skills(job_posting_id, skill, canonical_slug, requirement_level) VALUES (?, 'another spelling', 'kotlin', 'PREFERRED')", first.id.value)
    }
    crawl(db, site, listOf(original), 1)
    assertEquals(first.classification, repository.findByCareerSite(site).single().classification)
    assertEquals(first.updatedAt, repository.findByCareerSite(site).single().updatedAt)
    assertEquals(2, db.fetchCount(DSL.table("posting_skills")))

    val updated = original.copy(descriptionText = "Preferred:\nRust", employmentHint = "Other schedule", remoteHint = "Maybe",
      locationHint = "  Moon   Base  ")
    crawl(db, site, listOf(updated), 2)
    val second = repository.findByCareerSite(site).single()
    assertEquals(classifyPosting(updated), second.classification)
    assertEquals("Other schedule", second.classification!!.employment.rawValue)
    assertEquals("Maybe", second.classification!!.remote.rawValue)
    assertEquals(0, repository.search(Filter.HasSkill("kotlin"), PageRequest(10)).totalCount)
    assertEquals(1, db.fetchCount(DSL.table("posting_skills")))
    val anchor = raw("anchor", "Anchor", "")
    crawl(db, site, listOf(anchor), 3)
    crawl(db, site, listOf(anchor), 4)
    assertEquals(PostingStatus.CLOSED, repository.findByCareerSite(site).single { it.id == first.id }.status)
    assertEquals(second.classification, repository.findByCareerSite(site).single { it.id == first.id }.classification)
    crawl(db, site, listOf(original, anchor), 5)
    val reopened = repository.findByCareerSite(site).single { it.id == first.id }
    assertEquals(first.id, reopened.id)
    assertEquals(PostingStatus.OPEN, reopened.status)
    assertEquals(first.classification, reopened.classification)

    val run = JooqCrawlRunRepository(db).start(site, NOW.plusSeconds(6))
    val invalid = SyncPlan(update = listOf(PostingUpdate(PostingRef(first.id, "a"), updated,
      updated.contentHash(), NOW.plusSeconds(6))),
      touch = listOf(PostingTouch(PostingRef(JobPostingId(999), "missing"), NOW.plusSeconds(6))))
    assertFailsWith<IllegalStateException> {
      JooqSuccessfulCrawlAdapter(db).applyAndComplete(run, lease(db, site), invalid, 1, NOW.plusSeconds(6))
    }
    assertEquals(first.classification, repository.findByCareerSite(site).single { it.id == first.id }.classification)
  }

  @Test
  fun `enriched SQL leaves and generated nested filters equal the independent domain matcher including absent classification`() {
    val (_, db) = migratedContext()
    val firstSite = insertSite(db)
    val secondSite = insertSite(db, "other.example")
    val raws = listOf(raw("a", "Backend Developer", "Kotlin required", "정규직", "서울", "remote"),
      raw("b", "Frontend Developer", "Java preferred", "contract", "Busan", "onsite"),
      raw("c", "Mystery", "Rust", "not known", null, null))
    crawl(db, firstSite, raws, 0)
    crawl(db, secondSite, listOf(raw("d", "Backend", "Kotlin preferred", "internship", "Moon Base", "hybrid")), 0)
    legacyPosting(db, firstSite.value, "unenriched")
    db.execute("UPDATE job_postings SET status = 'CLOSED', closed_at = updated_at WHERE external_key = 'b'")
    val repository = JooqPostingRepository(db)
    // These literal results ensure two adapters cannot agree by both dropping enrichment.
    assertEquals(listOf("a"), repository.search(Filter.HasSkill("kotlin", SkillRequirementLevel.REQUIRED), PageRequest(100)).postings.map { it.raw.externalKey })
    assertEquals(setOf("a", "d"), repository.search(Filter.HasRole(RoleCategory.BACKEND), PageRequest(100)).postings.map { it.raw.externalKey }.toSet())
    val all = repository.search(Filter.And(emptyList()), PageRequest(100)).postings
    assertNull(all.single { it.raw.externalKey == "unenriched" }.classification)
    val atoms = listOf<Filter>(Filter.AtSite(firstSite), Filter.TextContains(" BACKEND "),
      Filter.HasStatus(PostingStatus.CLOSED), Filter.UpdatedAfter(NOW), Filter.HasSkill("kotlin"),
      Filter.HasSkill("kotlin", SkillRequirementLevel.PREFERRED), Filter.HasSkill("java", SkillRequirementLevel.REQUIRED),
      Filter.HasSkill("rust", SkillRequirementLevel.MENTIONED), Filter.HasRole(RoleCategory.BACKEND),
      Filter.HasRole(RoleCategory.UNKNOWN), Filter.HasEmployment(EmploymentType.FULL_TIME),
      Filter.HasEmployment(EmploymentType.UNKNOWN), Filter.HasRemotePolicy(RemotePolicy.REMOTE),
      Filter.HasRemotePolicy(RemotePolicy.UNKNOWN), Filter.AtLocation("seoul"), Filter.AtLocation("moon base"))
    val random = Random(817)
    fun generated(depth: Int): Filter = if (depth == 0) atoms.random(random) else when (random.nextInt(4)) {
      0 -> atoms.random(random)
      1 -> Filter.Not(generated(depth - 1))
      2 -> Filter.And(List(random.nextInt(4)) { generated(depth - 1) })
      else -> Filter.Or(List(random.nextInt(4)) { generated(depth - 1) })
    }
    (atoms + atoms.map(Filter::Not) + listOf(Filter.And(emptyList()), Filter.Or(emptyList())) +
      List(300) { generated(4) }).forEach { filter ->
      val expected = all.filter(filter::matches).map { it.id }.toSet()
      val actual = repository.search(filter, PageRequest(100))
      assertEquals(expected, actual.postings.map { it.id }.toSet(), filter.toString())
      assertEquals(expected.size.toLong(), actual.totalCount, filter.toString())
    }
    assertFailsWith<UnknownSkillException> { repository.search(Filter.HasSkill("no-such-skill"), PageRequest(10)) }
  }

  private fun crawl(db: DSLContext, site: CareerSiteId, raws: List<RawPosting>, seconds: Long) {
    val now = NOW.plusSeconds(seconds)
    val run = JooqCrawlRunRepository(db).start(site, now)
    val plan = assertIs<ReconciliationResult.Success>(reconcile(JooqPostingRepository(db).findByCareerSite(site),
      Snapshot(site, SiteHost(if (site.value == 1L) "jobs.example" else "other.example"), now, raws), ClosePolicy(2), now)).plan
    val adapter = JooqSuccessfulCrawlAdapter(db)
    val lease = lease(db, site)
    val counts = adapter.applyAndComplete(run, lease, plan, raws.size, now)
    assertEquals(counts, adapter.applyAndComplete(run, lease, plan, raws.size, now))
  }

  private fun lease(db: DSLContext, site: CareerSiteId): CrawlLease {
    JooqCrawlLeasePort(db).tryAcquire(site, "enrichment", NOW.minusSeconds(1), Duration.ofHours(1))
    return CrawlLease(site, "enrichment", NOW.plusSeconds(3599))
  }

  private fun insertSite(db: DSLContext, host: String = "jobs.example", name: String = "Acme"): CareerSiteId =
    assertIs<InsertCareerSiteResult.Inserted>(JooqCareerSiteRepository(db).insert(
      NewCareerSite(siteUrl("https://$host"), SourceProvider.FLEX, name))).site.id

  private fun legacyPosting(db: DSLContext, site: Long, key: String) {
    db.execute("""INSERT INTO job_postings(career_site_id, external_key, title, description_text, canonical_url,
      employment_hint, content_hash, status, first_seen_at, last_seen_at, updated_at)
      VALUES (?, ?, 'Legacy', 'Original text', 'https://jobs.example/old', 'Legacy unknown', ?, 'OPEN', ?::timestamptz, ?::timestamptz, ?::timestamptz)""",
      site, key, "0".repeat(64), NOW.atOffset(java.time.ZoneOffset.UTC), NOW.atOffset(java.time.ZoneOffset.UTC), NOW.atOffset(java.time.ZoneOffset.UTC))
  }

  private fun raw(key: String, title: String, description: String, employment: String? = null,
    location: String? = null, remote: String? = null) = RawPosting(key, title, description,
    assertIs<PostingUrlResult.Valid>(PostingUrl.parse("https://${if (key == "d") "other" else "jobs"}.example/$key")).url,
    employment, location, remote)

  private fun siteUrl(value: String) = assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
  private companion object { val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z") }
}
