package com.rigour.integration.api.v1.model;

import java.time.Instant;

public record StaffQueryCommand(Integer begin, Integer step, String staffType, String status,
                                String keywords, Instant createdFrom, Instant createdTo,
                                Instant updatedFrom, Instant updatedTo) {
}
