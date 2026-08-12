package com.rigour.erp.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StablePayloadHasherTest {
    @Test
    void hashDoesNotChangeWhenSourceMapInsertionOrderChanges() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("sourceFields", new LinkedHashMap<>(Map.of("z", 1, "a", 2)));
        first.put("name", "商品一");

        Map<String, Object> second = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 2);
        nested.put("z", 1);
        second.put("name", "商品一");
        second.put("sourceFields", nested);

        assertThat(StablePayloadHasher.sha256(JsonMapper.builder().build(), first))
                .isEqualTo(StablePayloadHasher.sha256(JsonMapper.builder().build(), second));
    }
}
