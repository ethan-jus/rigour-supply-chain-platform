package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.UUID;

public record DictionaryView(UUID id, String code, String name, String status,
                             Instant syncedAt, UUID parentId, String parentCode) {
}
