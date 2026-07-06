-- Add T1 image annotation fields to media_assets.
-- Run this migration on existing PostgreSQL databases.

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    object_annotations TEXT;

ALTER TABLE media_assets ADD COLUMN IF NOT EXISTS
    scene_label VARCHAR(100);
