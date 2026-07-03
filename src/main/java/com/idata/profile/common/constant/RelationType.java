package com.idata.profile.common.constant;

/**
 * account_relations.relation_type mapping.
 * The code field is the original relation string from KT3 Kafka messages and must not be changed.
 * All Neo4j relation labels come from the RZDK relationships.py RelationType enum vocabulary.
 */
public enum RelationType {
    FOLLOWING("following", "FOLLOWS"),
    SUBSCRIBE("subscribe", "MEMBER_OF"),
    MEMBER_OF("member_of", "MEMBER_OF"),
    OWNER_OF("owner_of", "OWNS"),
    CREATOR_OF("creator_of", "OWNS"),
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

    public String getNeo4jRelationLabel() {
        return neo4jRelationLabel;
    }

    public static RelationType fromCode(String code) {
        for (RelationType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown relation_type: " + code);
    }
}
