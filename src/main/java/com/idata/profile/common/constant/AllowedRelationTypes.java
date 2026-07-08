package com.idata.profile.common.constant;

import java.util.Set;

/**
 * T2/T3 关系抽取允许的关系类型词表，唯一权威来源。
 * 对齐当前产品确认的 16 个关系类型。
 * 若需要修改关系词表，只改这一个地方，不要在别处再复制一份Set字面量。
 */
public final class AllowedRelationTypes {

    public static final Set<String> VALUES = Set.of(
            "HAS_ACCOUNT",
            "BELONGS_TO", "PART_OF",
            "PUBLISHED_BY", "REPLY_TO", "REPOSTS", "MENTIONS",
            "DESCRIBES", "EVENT_OCCURRED_AT", "EVENT_INVOLVES_ENTITY", "LOCATED_IN",
            "SUPPORTS", "OPPOSES", "QUESTIONS", "INCITES", "DE_ESCALATES"
    );

    private AllowedRelationTypes() {
    }
}
