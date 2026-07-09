ALTER TABLE social_accounts ADD COLUMN IF NOT EXISTS identity_resolved_at TIMESTAMPTZ;
COMMENT ON COLUMN social_accounts.identity_resolved_at IS
  '身份识别（匹配/新建Person或Organization、或判定跳过）完成时间，NULL表示还未处理';
