package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class FieldworkSources {
    private FieldworkSources() {}

    public record Query(LocalDate date, Instant asOf, List<String> employees, List<Long> visits) {}

    public record Contact(String key, Instant addedAt) {}

    public record Contacts(String status, Instant observedAt, List<Contact> additions) {}

    public record Visit(String state, String reason, String ruleVersion, Integer minimumMinutes) {}

    public record Result(Map<String, Contacts> contacts, Map<Long, Visit> visits) {}
}
