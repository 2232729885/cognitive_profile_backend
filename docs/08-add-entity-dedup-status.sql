-- Execute this migration in PostgreSQL when switching entity fusion from
-- write-time upsert to background EntityDeduplicationJob.

ALTER TABLE persons ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE events ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';
ALTER TABLE narratives ADD COLUMN IF NOT EXISTS
    dedup_status VARCHAR(20) NOT NULL DEFAULT 'pending';

-- Duplicate canonical names/labels are now allowed. Background dedup will
-- choose canonical survivors later.
DROP INDEX IF EXISTS uq_persons_canonical_name;
DROP INDEX IF EXISTS uq_orgs_canonical_name;
DROP INDEX IF EXISTS uq_events_canonical_name;
DROP INDEX IF EXISTS uq_narratives_canonical_label;

CREATE INDEX IF NOT EXISTS idx_persons_dedup ON persons(dedup_status);
CREATE INDEX IF NOT EXISTS idx_orgs_dedup ON organizations(dedup_status);
CREATE INDEX IF NOT EXISTS idx_events_dedup ON events(dedup_status);
CREATE INDEX IF NOT EXISTS idx_narratives_dedup ON narratives(dedup_status);
