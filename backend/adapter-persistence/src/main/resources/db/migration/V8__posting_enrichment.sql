-- Additive enrichment. Legacy source strings remain available without inventing classifications.
-- ASCII-only policy: lowercase ASCII letters/digits; other characters become separators.
-- We deliberately do not transliterate Unicode. Every slug includes its immutable database ID,
-- so equal names, fallback names, concurrent inserts and migration scan order cannot collide.
CREATE FUNCTION career_site_slug(display_name TEXT, site_id BIGINT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT COALESCE(NULLIF(trim(both '-' FROM regexp_replace(lower(display_name COLLATE "C"), '[^a-z0-9]+', '-', 'g')), ''), 'company')
    || '-' || site_id::text
$$;

ALTER TABLE career_sites ADD COLUMN slug TEXT;
UPDATE career_sites SET slug = career_site_slug(display_name, id);
ALTER TABLE career_sites ALTER COLUMN slug SET NOT NULL;
ALTER TABLE career_sites ADD CONSTRAINT uq_career_sites_slug UNIQUE (slug);
ALTER TABLE career_sites ADD CONSTRAINT ck_career_sites_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

CREATE FUNCTION maintain_career_site_slug() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.slug := career_site_slug(NEW.display_name, NEW.id);
  ELSIF NEW.slug IS DISTINCT FROM OLD.slug OR NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'Career site identity and slug are immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER career_site_slug_stability BEFORE INSERT OR UPDATE ON career_sites
  FOR EACH ROW EXECUTE FUNCTION maintain_career_site_slug();

ALTER TABLE job_postings
  ADD COLUMN taxonomy_version INTEGER CHECK (taxonomy_version > 0),
  ADD COLUMN role_category TEXT CHECK (role_category IN ('BACKEND', 'FRONTEND', 'FULLSTACK', 'MOBILE', 'DATA', 'DEVOPS', 'DESIGN', 'PRODUCT', 'QA', 'UNKNOWN')),
  ADD COLUMN employment_type TEXT CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP', 'UNKNOWN')),
  ADD COLUMN remote_policy TEXT CHECK (remote_policy IN ('REMOTE', 'HYBRID', 'ONSITE', 'UNKNOWN')),
  ADD COLUMN location_display_name TEXT,
  ADD COLUMN location_search_value TEXT,
  ADD COLUMN unknown_skill_mentions JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(unknown_skill_mentions) = 'array'),
  ADD CONSTRAINT ck_posting_classification CHECK (
    (taxonomy_version IS NULL AND role_category IS NULL AND employment_type IS NULL AND remote_policy IS NULL
      AND location_display_name IS NULL AND location_search_value IS NULL) OR
    (taxonomy_version IS NOT NULL AND role_category IS NOT NULL AND employment_type IS NOT NULL AND remote_policy IS NOT NULL)
  ),
  ADD CONSTRAINT ck_posting_location CHECK (
    (location_display_name IS NULL AND location_search_value IS NULL) OR
    (location_display_name IS NOT NULL AND location_search_value IS NOT NULL
      AND location_display_name <> '' AND location_search_value <> '')
  );

ALTER TABLE posting_skills
  ADD COLUMN requirement_level TEXT NOT NULL DEFAULT 'MENTIONED'
    CHECK (requirement_level IN ('REQUIRED', 'PREFERRED', 'MENTIONED')),
  ADD COLUMN canonical_slug TEXT CHECK (canonical_slug ~ '^[a-z][a-z0-9-]*$'),
  ADD COLUMN mention_text TEXT,
  ADD COLUMN mention_order INTEGER NOT NULL DEFAULT 0 CHECK (mention_order >= 0);

-- Freeze the V1 alias map in this migration: future catalogs must not alter an applied backfill.
-- Multiple legacy aliases retain every raw row; only one carries the canonical association.
WITH taxonomy(slug, aliases) AS (VALUES
  ('kotlin', ARRAY['kotlin', '코틀린']),
  ('java', ARRAY['java', '자바']),
  ('spring', ARRAY['spring', 'spring framework', '스프링', '스프링 프레임워크']),
  ('javascript', ARRAY['javascript', 'js', '자바스크립트']),
  ('typescript', ARRAY['typescript', 'ts', '타입스크립트']),
  ('react', ARRAY['react', 'react.js', 'reactjs', '리액트']),
  ('vue', ARRAY['vue', 'vue.js', 'vuejs', '뷰']),
  ('solidjs', ARRAY['solidjs', 'solid.js', '솔리드js']),
  ('nodejs', ARRAY['nodejs', 'node.js', '노드js', '노드']),
  ('python', ARRAY['python', '파이썬']),
  ('django', ARRAY['django', '장고']),
  ('fastapi', ARRAY['fastapi', 'fast api', '패스트api']),
  ('go', ARRAY['go', 'golang', '골랭', '고언어']),
  ('rust', ARRAY['rust', '러스트']),
  ('c', ARRAY['c', 'c언어', '씨언어']),
  ('cpp', ARRAY['cpp', 'c++', '씨플러스플러스']),
  ('csharp', ARRAY['csharp', 'c#', '씨샵', '씨샤프']),
  ('dotnet', ARRAY['dotnet', '.net', '닷넷']),
  ('swift', ARRAY['swift', '스위프트']),
  ('ios', ARRAY['ios', '아이오에스']),
  ('android', ARRAY['android', '안드로이드']),
  ('flutter', ARRAY['flutter', '플러터']),
  ('postgresql', ARRAY['postgresql', 'postgres', '포스트그레스', '포스트그레sql']),
  ('mysql', ARRAY['mysql', '마이에스큐엘']),
  ('redis', ARRAY['redis', '레디스']),
  ('mongodb', ARRAY['mongodb', 'mongo', '몽고db', '몽고디비']),
  ('kafka', ARRAY['kafka', 'apache kafka', '카프카']),
  ('aws', ARRAY['aws', 'amazon web services', '아마존 웹 서비스']),
  ('gcp', ARRAY['gcp', 'google cloud', 'google cloud platform', '구글 클라우드']),
  ('azure', ARRAY['azure', 'microsoft azure', '애저', '애저 클라우드']),
  ('docker', ARRAY['docker', '도커']),
  ('kubernetes', ARRAY['kubernetes', 'k8s', '쿠버네티스']),
  ('terraform', ARRAY['terraform', '테라폼']),
  ('linux', ARRAY['linux', '리눅스']),
  ('git', ARRAY['git', '깃']),
  ('graphql', ARRAY['graphql', '그래프큐엘', '그래프ql']),
  ('rest', ARRAY['rest', 'restful', '레스트']),
  ('spark', ARRAY['spark', 'apache spark', '스파크']),
  ('airflow', ARRAY['airflow', 'apache airflow', '에어플로우']),
  ('pytorch', ARRAY['pytorch', '파이토치']),
  ('tensorflow', ARRAY['tensorflow', '텐서플로', '텐서플로우'])
), resolved AS (
  SELECT s.job_posting_id, s.skill, t.slug,
    row_number() OVER (PARTITION BY s.job_posting_id, t.slug ORDER BY s.skill COLLATE "C") AS ordinal
  FROM posting_skills s JOIN taxonomy t
    ON lower(s.skill COLLATE "C") = ANY(t.aliases)
)
UPDATE posting_skills s SET canonical_slug = r.slug
FROM resolved r WHERE s.job_posting_id = r.job_posting_id AND s.skill = r.skill AND r.ordinal = 1;

UPDATE posting_skills SET mention_text = skill;
WITH ordered AS (
  SELECT job_posting_id, skill,
    row_number() OVER (PARTITION BY job_posting_id ORDER BY canonical_slug COLLATE "C" NULLS LAST, skill COLLATE "C") - 1 AS ordinal
  FROM posting_skills
)
UPDATE posting_skills s SET mention_order = o.ordinal
FROM ordered o WHERE s.job_posting_id = o.job_posting_id AND s.skill = o.skill;
CREATE UNIQUE INDEX uq_posting_canonical_skill ON posting_skills(job_posting_id, canonical_slug)
  WHERE canonical_slug IS NOT NULL;
CREATE INDEX ix_posting_skill_filter ON posting_skills(canonical_slug, requirement_level, job_posting_id)
  WHERE canonical_slug IS NOT NULL;

-- Past hints have no authoritative classifier result yet. Preserve the hints and legacy skills,
-- then refresh classification on the next successful observation, including unchanged postings.
UPDATE job_postings SET taxonomy_version = 1, role_category = 'UNKNOWN',
  employment_type = 'UNKNOWN', remote_policy = 'UNKNOWN';
CREATE INDEX ix_posting_role ON job_postings(role_category);
CREATE INDEX ix_posting_location ON job_postings(location_search_value);
