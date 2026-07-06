-- Add T1 image annotation fields to media_assets.
-- Run this migration on existing PostgreSQL databases.

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    object_annotations TEXT;

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    scene_label VARCHAR(100);

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    t1_annotated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_media_assets_t1_annotated
    ON media_assets(t1_annotated) WHERE t1_annotated = FALSE;
