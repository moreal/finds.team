-- Keep bounded orphan maintenance scans proportional to unfinished work, not crawl history.
CREATE INDEX ix_crawl_runs_pending_started ON crawl_runs (started_at, id)
  WHERE finished_at IS NULL;
