package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;

import java.time.Instant;
import java.util.List;

public interface TargetSettingsStore {
    java.util.Set<String> permittedSubjects(String tenant, String action);

    List<Subject> subjects(String tenant);

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
