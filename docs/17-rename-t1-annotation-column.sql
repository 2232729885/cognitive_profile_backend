ALTER TABLE media_contents RENAME COLUMN t1_annotation_v05 TO t1_annotation;
COMMENT ON COLUMN media_contents.t1_annotation IS 'T1完整标注结果JSON（当前schema_version见字段内容本身，不体现在列名里）';
