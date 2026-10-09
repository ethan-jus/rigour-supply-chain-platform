package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import java.time.Instant;
import java.util.List;

public interface TargetSettingsStore {
    boolean permitted(String tenant, String type, String code, String action);
    List<Subject> subjects(String tenant);
    List<TargetOverride> overrides(String tenant, String month);
    List<DefaultRule> defaults(String tenant);
    void save(String tenant, String actor, String month, Subject subject, Change change, String reason, Instant now);
    void saveDefault(String tenant, String actor, String month, String type, DefaultChange change, String reason, Instant now);
    List<History> history(String tenant, String month, String type, String code);
}
