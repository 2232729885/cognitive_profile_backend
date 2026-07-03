-- Execute this migration in PostgreSQL before enabling ContentPropagationBackfillJob.
-- It adds the media_contents flag used to retry propagation-chain Neo4j writes
-- when target media contents arrive after the source message.

ALTER TABLE media_contents ADD COLUMN IF NOT EXISTS
    propagation_synced_to_neo4j BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_mc_pending_propagation_sync
    ON media_contents(propagation_synced_to_neo4j)
    WHERE propagation_synced_to_neo4j = FALSE;
