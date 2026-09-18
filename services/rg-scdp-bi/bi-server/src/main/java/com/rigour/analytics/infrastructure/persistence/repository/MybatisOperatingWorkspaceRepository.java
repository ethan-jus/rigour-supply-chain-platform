package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore;
import com.rigour.analytics.application.service.OperatingWorkspaceService;
import com.rigour.analytics.infrastructure.persistence.mapper.OperatingWorkspaceMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 乐观更新和审计同事务；失败不留下成功状态或半条处理记录。 */
@Repository
public class MybatisOperatingWorkspaceRepository implements OperatingWorkspaceStore {
    private final OperatingWorkspaceMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public MybatisOperatingWorkspaceRepository(OperatingWorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<TargetView> targets(
            String tenant, LocalDate month, String type, String code, String region, String owner) {
        return mapper.targets(tenant, month, type, code, region, owner);
    }

    @Override
    public Optional<TargetView> target(String tenant, String id) {
        return Optional.ofNullable(mapper.target(tenant, id));
    }

    @Override
    public List<String> targetRegions(String tenant, String type, String code) {
        var context =
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().orElse(null);
        if (context != null && context.active()) {
            if ("CITY".equals(type))
                return jdbc.queryForList(
                        "SELECT region_code FROM bi_region_authority WHERE tenant_id=? AND"
                            + " region_code=?",
                        String.class,
                        tenant,
                        code);
            return jdbc.queryForList(
                    "SELECT DISTINCT COALESCE(region_code,'') FROM (SELECT"
                        + " tenant_id,employee_code,region_code FROM bi_customer_authority UNION"
                        + " SELECT tenant_id,employee_code,region_code FROM bi_order_authority)"
                        + " footprint WHERE tenant_id=? AND employee_code=?",
                    String.class,
                    tenant,
                    code);
        }
        return mapper.targetRegions(tenant, type, code);
    }

    @Override
    public List<BusinessSubject> subjects(String tenant, String kind, String ref) {
        if ("STOCK".equals(kind))
            return mapper.stockSubject(
                    tenant, ref.startsWith("product-code:") ? ref.substring(13) : ref);
        if ("COLLECTION".equals(kind) && ref.startsWith("order-id:"))
            return mapper.orderSubject(tenant, ref.substring(9));
        boolean byId = ref.startsWith("customer-id:");
        return mapper.customerSubject(
                tenant,
                byId
                        ? ref.substring(12)
                        : ref.startsWith("customer-code:") ? ref.substring(14) : ref,
                byId);
    }

    @Override
    @Transactional
    public TargetView saveTarget(String tenant, String actor, TargetCommand command, Instant now) {
        LocalDate month = YearMonth.parse(command.month()).atDay(1);
        if (command.expectedRevision() == 0) {
            try {
                mapper.insertTarget(tenant, actor, month, command, now);
            } catch (DuplicateKeyException exception) {
                if (mapper.restoreTarget(tenant, actor, month, command, now) != 1)
                    throw OperatingWorkspaceService.conflict();
            }
        } else if (mapper.updateTarget(tenant, actor, month, command, now) != 1) {
            throw OperatingWorkspaceService.conflict();
        }
        var row = mapper.targetKey(tenant, month, command);
        mapper.targetEvent(tenant, actor, row.id(), UUID.randomUUID().toString(), now);
        return row;
    }

    @Override
    @Transactional
    public boolean deleteTarget(String tenant, String actor, String id, int revision, Instant now) {
        if (mapper.deleteTarget(tenant, actor, id, revision, now) != 1) return false;
        mapper.targetEvent(tenant, actor, id, UUID.randomUUID().toString(), now);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public ActionPage actions(String tenant, ActionFilter filter) {
        return new ActionPage(
                mapper.actions(tenant, filter),
                mapper.actionCount(tenant, filter),
                filter.page(),
                filter.pageSize());
    }

    @Override
    public Optional<ActionView> action(String tenant, String id) {
        return Optional.ofNullable(mapper.action(tenant, id));
    }

    @Override
    @Transactional
    public ActionView createAction(
            String tenant, String actor, ActionCommand command, Instant now) {
        String id = UUID.randomUUID().toString();
        mapper.insertAction(tenant, actor, id, command, now);
        var result = mapper.action(tenant, id);
        event(tenant, actor, null, result, now);
        return result;
    }

    @Override
    @Transactional
    public Optional<ActionView> updateAction(
            String tenant,
            String actor,
            ActionView previous,
            ActionUpdateCommand command,
            Instant now) {
        if (mapper.updateAction(tenant, previous.id(), command, now) != 1) return Optional.empty();
        var result = mapper.action(tenant, previous.id());
        event(tenant, actor, previous, result, now);
        return Optional.of(result);
    }

    private void event(
            String tenant, String actor, ActionView previous, ActionView result, Instant now) {
        mapper.insertEvent(
                tenant,
                new ActionEventView(
                        UUID.randomUUID().toString(),
                        result.id(),
                        result.revision(),
                        previous == null ? null : previous.status(),
                        result.status(),
                        previous == null ? null : previous.assignee(),
                        result.assignee(),
                        previous == null ? null : previous.dueAt(),
                        result.dueAt(),
                        result.note(),
                        actor,
                        now));
    }

    @Override
    public List<ActionEventView> events(String tenant, String id) {
        return mapper.events(tenant, id);
    }
}
