ALTER TABLE media_contents ADD COLUMN IF NOT EXISTS source_media_asset_ids VARCHAR[];
COMMENT ON COLUMN media_contents.source_media_asset_ids IS
  '社交内容消息里 data.media_asset_ids 原始字符串ID列表，用于反查关联 media_assets.source_asset_id';
