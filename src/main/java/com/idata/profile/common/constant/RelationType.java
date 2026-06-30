package com.idata.profile.common.constant;

/**
 * account_relations.relation_type。
 * 课题三直接交付的事实关系，confidence 默认 1.0，
 * 区别于 T6 推断的协同关系（COORDINATES_WITH，写入Neo4j时 detected_by='T6'）。
 */
public enum RelationType {
    FOLLOWING("following", "FOLLOWS"),
    SUBSCRIBE("subscribe", "SUBSCRIBES_TO"),
    MEMBER_OF("member_of", "MEMBER_OF_GROUP"),
    OWNER_OF("owner_of", "OWNER_OF"),
    CREATOR_OF("creator_of", "OWNER_OF"),
    ADMIN_OF("admin_of", "ADMIN_OF"),
    MODERATOR_OF("moderator_of", "ADMIN_OF");

    private final String code;
    private final String neo4jRelationLabel;

    RelationType(String code, String neo4jRelationLabel) {
        this.code = code;
        this.neo4jRelationLabel = neo4jRelationLabel;
    }

    public String getCode() {
        return code;
    }

    /** 回填批处理写入 Neo4j 时使用的关系标签，见 batch.relation.AccountRelationBackfillJob */
    public String getNeo4jRelationLabel() {
        return neo4jRelationLabel;
    }

    public static RelationType fromCode(String code) {
        for (RelationType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 relation_type: " + code);
    }
}
