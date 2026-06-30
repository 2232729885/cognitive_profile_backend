-- ============================================================
-- 补充sub_agent_registry的mock_url字段
-- init_db.sql建表时只设置了active_url_type='mock'，mock_url留空
-- 这条SQL在执行完init_db.sql之后单独运行一次即可
-- 端口8080需与application.yml里server.port保持一致
-- ============================================================

UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t1' WHERE agent_code = 'T1';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t2' WHERE agent_code = 'T2';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t3' WHERE agent_code = 'T3';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t4' WHERE agent_code = 'T4';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t5' WHERE agent_code = 'T5';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t6' WHERE agent_code = 'T6';

-- 验证
SELECT agent_code, active_url_type, mock_url, is_active FROM sub_agent_registry ORDER BY agent_code;
