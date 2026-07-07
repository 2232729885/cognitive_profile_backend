-- Backfill media_contents.media_asset_ids from media_assets.content_id.
-- Each media_content receives the ordered asset id array for its linked media_assets.
UPDATE media_contents mc
SET media_asset_ids = subq.asset_ids
FROM (
    SELECT content_id,
           array_agg(id ORDER BY created_at) AS asset_ids
    FROM media_assets
    WHERE content_id IS NOT NULL
    GROUP BY content_id
) subq
WHERE mc.id = subq.content_id;

-- Verify that contents with linked assets now have non-empty media_asset_ids.
SELECT id, media_asset_ids
FROM media_contents
WHERE media_asset_ids IS NOT NULL
LIMIT 5;
