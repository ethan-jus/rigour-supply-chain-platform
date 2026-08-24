package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

public record DictionaryView(UUID id, String code, String name, String status,
                             Instant syncedAt, UUID parentId, String parentCode,
                             /** PRESENT=已见；ABSENT_CANDIDATE=待确认；ABSENT/DELETED=来源已删除。 */
                             String sourcePresence,
                             /** 首次完整快照未见的时间。 */ Instant sourceAbsentAt) {
    public DictionaryView(UUID id, String code, String name, String status,
                          Instant syncedAt, UUID parentId, String parentCode) {
        this(id, code, name, status, syncedAt, parentId, parentCode, null, null);
    }
}
