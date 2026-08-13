package com.rigour.integration.api.v1.model;

import java.util.Map;

public record CustomerTypeView(String sourceId, String name, String erpId,
                               Map<String, Object> sourceFields) {
}
