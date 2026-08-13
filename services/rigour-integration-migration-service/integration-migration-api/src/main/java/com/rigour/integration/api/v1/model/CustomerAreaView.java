package com.rigour.integration.api.v1.model;

import java.util.Map;

public record CustomerAreaView(String sourceId, String name, String erpId,
                               String parentSourceId, Map<String, Object> sourceFields) {
}
