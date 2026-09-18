package com.rigour.integration.api.v1.model;

import java.time.Instant;

public record CustomerQueryCommand(Integer begin, Integer step, Integer status, Integer dataType,
                                   String timeType, Instant from, Instant to, String clientNo,
                                   Integer clientArea, Integer typeId) {
}
