-- Pick two existing social_accounts on the same platform, insert one pending
-- account_relations row with null UUID foreign keys, then query the row after
-- calling POST /debug/trigger-relation-backfill.
--
-- If this INSERT returns 0 rows, the database does not currently have two
-- social_accounts records on the same platform.

WITH candidate_platform AS (
    SELECT platform
    FROM social_accounts
    WHERE platform IS NOT NULL
      AND platform_user_id IS NOT NULL
    GROUP BY platform
    HAVING COUNT(*) >= 2
    ORDER BY COUNT(*) DESC, platform
    LIMIT 1
),
picked_accounts AS (
    SELECT
        sa.id,
        sa.platform,
        sa.platform_user_id,
        ROW_NUMBER() OVER (
            ORDER BY COALESCE(sa.updated_at, sa.created_at) DESC, sa.id
        ) AS rn
    FROM social_accounts sa
    JOIN candidate_platform cp ON cp.platform = sa.platform
    WHERE sa.platform_user_id IS NOT NULL
),
account_pair AS (
    SELECT
        src.platform,
        src.id AS expected_from_account_id,
        src.platform_user_id AS source_platform_user_id,
        dst.id AS expected_to_account_id,
        dst.platform_user_id AS target_platform_user_id
    FROM picked_accounts src
    JOIN picked_accounts dst ON src.rn = 1 AND dst.rn = 2
)
INSERT INTO account_relations (
    source_platform_user_id,
    target_platform_user_id,
    platform,
    relation_type,
    observed_at,
    source,
    confidence,
    from_account_id,
    to_account_id,
    synced_to_neo4j
)
SELECT
    source_platform_user_id,
    target_platform_user_id,
    platform,
    'following',
    NOW(),
    'debug_relation_backfill_manual',
    1.0,
    NULL,
    NULL,
    FALSE
FROM account_pair
RETURNING
    id,
    platform,
    source_platform_user_id,
    target_platform_user_id,
    from_account_id,
    to_account_id,
    synced_to_neo4j;

-- Run after calling:
--   POST http://localhost:8080/debug/trigger-relation-backfill
SELECT
    ar.id,
    ar.platform,
    ar.source_platform_user_id,
    ar.target_platform_user_id,
    ar.from_account_id,
    src.id AS expected_from_account_id,
    ar.from_account_id = src.id AS from_backfilled_ok,
    ar.to_account_id,
    dst.id AS expected_to_account_id,
    ar.to_account_id = dst.id AS to_backfilled_ok,
    ar.synced_to_neo4j,
    ar.created_at
FROM account_relations ar
JOIN social_accounts src
  ON src.platform = ar.platform
 AND src.platform_user_id = ar.source_platform_user_id
JOIN social_accounts dst
  ON dst.platform = ar.platform
 AND dst.platform_user_id = ar.target_platform_user_id
WHERE ar.source = 'debug_relation_backfill_manual'
ORDER BY ar.created_at DESC
LIMIT 5;
