package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** BI 自有目标与跟进持久化端口；事务同时保存版本更新和不可变历史。 */
public interface OperatingWorkspaceStore {
    List<TargetView> targets(String tenant, LocalDate month, String type, String code, String region, String owner);
    Optional<TargetView> target(String tenant, String id);
    List<String> targetRegions(String tenant, String type, String code);
    List<BusinessSubject> subjects(String tenant, String kind, String businessRef);
    TargetView saveTarget(String tenant, String actor, TargetCommand command, Instant now);
    boolean deleteTarget(String tenant, String actor, String id, int revision, Instant now);
    ActionPage actions(String tenant, ActionFilter filter);
    Optional<ActionView> action(String tenant, String id);
    ActionView createAction(String tenant, String actor, ActionCommand command, Instant now);
    Optional<ActionView> updateAction(String tenant, String actor, ActionView previous,
            ActionUpdateCommand command, Instant now);
    List<ActionEventView> events(String tenant, String id);
    record BusinessSubject(String businessRef, String businessLabel, String cityCode, String employeeCode) {}
    record ActionFilter(String kind, String businessRef, String cityCode, String employeeCode,
            String assignee, String status, int page, int pageSize, boolean includeStock) {
        public int offset() { return (page - 1) * pageSize; }
    }
}
