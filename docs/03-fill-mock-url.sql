-- ============================================================
-- Fill sub_agent_registry mock_url values after init_db.sql.
-- Run once after database initialization.
-- ============================================================

UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t1' WHERE agent_code = 'T1';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t2' WHERE agent_code = 'T2';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t4' WHERE agent_code = 'T4';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t5' WHERE agent_code = 'T5';
UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t6' WHERE agent_code = 'T6';

INSERT INTO sub_agent_registry (
    id, agent_code, agent_name, description,
    mock_url, base_url, active_url_type,
    timeout_seconds, max_retries, health_status
) VALUES (
    gen_random_uuid(), 'T3', 'T3 信息融合', '跨语言实体归一和关系融合',
    'http://localhost:8080/mock/t3',
    NULL,
    'mock',
    60, 2, 'unknown'
) ON CONFLICT (agent_code) DO UPDATE SET
    agent_name = EXCLUDED.agent_name,
    description = EXCLUDED.description,
    mock_url = EXCLUDED.mock_url,
    active_url_type = EXCLUDED.active_url_type,
    timeout_seconds = EXCLUDED.timeout_seconds,
    max_retries = EXCLUDED.max_retries,
    is_active = TRUE,
    updated_at = NOW();

SELECT agent_code, active_url_type, mock_url, timeout_seconds, is_active
FROM sub_agent_registry
ORDER BY agent_code;

-- 切换 T1/T2 为本地大模型版本（Qwen3-VL-32B）
-- 执行以下 SQL 后重启项目即可，无需改代码：
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t1', active_url_type = 'mock' WHERE agent_code = 'T1';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t2', active_url_type = 'mock' WHERE agent_code = 'T2';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t3', active_url_type = 'mock' WHERE agent_code = 'T3';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t4', active_url_type = 'mock' WHERE agent_code = 'T4';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t5', active_url_type = 'mock' WHERE agent_code = 'T5';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/llm/t6', active_url_type = 'mock' WHERE agent_code = 'T6';
-- 切换回固定 Mock 数据：
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t1' WHERE agent_code = 'T1';
-- UPDATE sub_agent_registry SET mock_url = 'http://localhost:8080/mock/t2' WHERE agent_code = 'T2';
