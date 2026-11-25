package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

public interface TargetSettingsStore {
    java.util.Set<String> permittedSubjects(String tenant, String action);

    List<Subject> subjects(String tenant);

    boolean canReadAllArchivedSubjects();

    void lockOrganization(String tenant);

    List<Subject> historicalSubjects(String tenant, YearMonth month);

    java.util.Optional<Settings> archive(String tenant, YearMonth month);

    /** Writes at most one snapshot per tenant/month and returns the winning committed value. */
    Settings archive(String tenant, YearMonth month, Settings snapshot);

    List<Target> targets(String tenant, String from, String to);

    void save(
            String tenant,
            String actor,
            String month,
            Subject subject,
            Change change,
            String reason,
            Instant now);

    List<History> history(String tenant, String month, String type, String code);
}
