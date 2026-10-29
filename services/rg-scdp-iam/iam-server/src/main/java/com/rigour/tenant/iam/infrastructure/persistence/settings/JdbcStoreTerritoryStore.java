package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot.Area;
import com.rigour.tenant.iam.application.port.out.StoreTerritoryStore;
import com.rigour.tenant.iam.application.port.out.StoreDivisionClient;
import com.rigour.tenant.iam.application.port.out.AppReferenceClient;
import com.rigour.tenant.iam.application.model.settings.StoreTerritoryModels;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcStoreTerritoryStore implements StoreTerritoryStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final AppReferenceClient references;
    private final StoreDivisionClient divisions;
    private final TransactionTemplate tx;

    public JdbcStoreTerritoryStore(JdbcTemplate jdbc, JdbcAppSettingsStore settings,
            AppReferenceClient references, StoreDivisionClient divisions, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.references = references;
        this.divisions = divisions;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StoreTerritoryModels.Context management(Actor actor) {
        settings.requirePermission(actor, "supply:territory:read");
        var catalog = divisions.published(actor.tenantId());
        var departments = references.references(actor.tenantId(), "DEPARTMENT");
        Map<String, List<Area>> scopes = new HashMap<>();
        jdbc.query("SELECT department_id,admin_area_version,admin_area_code FROM department_admin_scope WHERE tenant_id=? AND status_code='ACTIVE' ORDER BY admin_area_version,admin_area_code",
                rs -> { scopes.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(new Area(rs.getString(2), rs.getString(3))); },
                actor.tenantId().toString());
        var rows = departments.stream().map(department -> new StoreTerritoryModels.Department(
                department.key(), department.name(), department.parentKey(), department.status(),
                List.copyOf(scopes.getOrDefault(department.key(), List.of())))).toList();
        return new StoreTerritoryModels.Context(version(actor), settings.permissions(actor).contains("supply:territory:write"), catalog, rows);
    }

    @Override
    public void saveTerritory(Actor actor, String department, StoreTerritoryModels.Update command) {
        settings.requirePermission(actor, "supply:territory:write");
        if (department == null || !department.matches("[1-9][0-9]{0,18}"))
            throw new RequestValidationException("部门ID无效");
        try { Long.parseLong(department); }
        catch (NumberFormatException exception) { throw new RequestValidationException("部门ID无效"); }
        if (command == null || command.applicationVersion() < 1 || command.areaCodes() == null
                || command.areaCodes().size() > 500 || command.reason() == null
                || command.reason().isBlank() || command.reason().length() > 200)
            throw new RequestValidationException("请填写有效的范围和修改原因（不超过200字）");
        var departmentRow = references.references(actor.tenantId(), "DEPARTMENT").stream()
                .filter(row -> department.equals(row.key()) && "ACTIVE".equals(row.status())).findFirst();
        if (departmentRow.isEmpty()) throw new RequestValidationException("请选择本租户的启用部门");
        var catalog = divisions.published(actor.tenantId());
        if (!catalog.version().equals(command.catalogVersion()))
            throw new StateConflictException("行政区划版本已变化，请刷新后重选范围");
        var nodes = new HashMap<String, com.rigour.settings.api.v1.model.AdminDivisionCatalog.Node>();
        catalog.nodes().forEach(node -> nodes.put(node.code(), node));
        var codes = new TreeSet<String>();
        for (String code : command.areaCodes()) {
            if (code == null || !code.matches("[0-9]{6}") || !nodes.containsKey(code))
                throw new RequestValidationException("范围包含不存在或已停用的行政区划");
            codes.add(code);
        }
        // A selected ancestor already includes its descendants; persist one unambiguous range.
        var normalized = codes.stream().filter(code -> {
            String parent = nodes.get(code).parentCode();
            while (parent != null) {
                if (codes.contains(parent)) return false;
                var node = nodes.get(parent);
                parent = node == null ? null : node.parentCode();
            }
            return true;
        }).map(code -> new Area(catalog.version(), code)).toList();
        tx.executeWithoutResult(status -> {
            settings.lock(actor);
            settings.requirePermission(actor, "supply:territory:write");
            if (version(actor) != command.applicationVersion())
                throw new StateConflictException("权限配置已被修改，请刷新后重新保存");
            var before = departments(actor.tenantId().toString(), List.of(department));
            var removed = before.stream().filter(area -> !normalized.contains(area)).toList();
            var added = normalized.stream().filter(area -> !before.contains(area)).toList();
            if (removed.isEmpty() && added.isEmpty()) return;
            String tenant = actor.tenantId().toString(), user = actor.principalId().toString();
            for (var area : removed) {
                jdbc.update("UPDATE department_admin_scope SET status_code='DISABLED',revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=? WHERE tenant_id=? AND department_id=? AND admin_area_version=? AND admin_area_code=? AND status_code='ACTIVE'",
                        user, tenant, department, area.version(), area.code());
                audit(actor, department, area, "ACTIVE → DISABLED", command.reason());
            }
            for (var area : added) {
                jdbc.update("""
                        INSERT INTO department_admin_scope(tenant_id,department_id,admin_area_version,admin_area_code,status_code,created_at,updated_at,created_by,updated_by)
                        VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)
                        ON DUPLICATE KEY UPDATE status_code='ACTIVE',revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=?
                        """, tenant, department, area.version(), area.code(), user, user, user);
                audit(actor, department, area, "未启用 → ACTIVE", command.reason());
            }
            settings.bump(actor);
        });
    }

    private long version(Actor actor) {
        return jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=? AND application_id=?",
                Long.class, JdbcAppSettingsStore.bin(actor.tenantId()), JdbcAppSettingsStore.bin(settings.applicationId()));
    }

    private void audit(Actor actor, String department, Area area, String change, String reason) {
        settings.audit(actor, "STORE_TERRITORY_CHANGE", department,
                "部门=" + department + "；行政范围=" + area.version() + ":" + area.code() + "；" + change + "；原因=" + reason.strip());
    }

    public List<Area> departments(String tenant, List<String> departments) {
        if (departments.isEmpty()) return List.of();
        var args = new ArrayList<Object>(); args.add(tenant); args.addAll(departments);
        return jdbc.query("SELECT DISTINCT admin_area_version,admin_area_code FROM department_admin_scope WHERE tenant_id=? AND status_code='ACTIVE' AND department_id IN ("
                + String.join(",", Collections.nCopies(departments.size(), "?")) + ")",
                (rs, n) -> new Area(rs.getString(1), rs.getString(2)), args.toArray());
    }

    public Ceiling personal(String tenant, String user) {
        String where = " tenant_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?) AND application_id=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN') AND dimension='ADMIN_REGION'";
        var modes = jdbc.queryForList("SELECT scope_mode FROM iam_app_member_scope_limit WHERE" + where, String.class, tenant, user);
        if (modes.isEmpty() || "ALL".equals(modes.getFirst())) return new Ceiling(false, List.of());
        if (!"SPECIFIED".equals(modes.getFirst())) return new Ceiling(true, List.of());
        var refs = jdbc.queryForList("SELECT reference_key FROM iam_app_member_limit_reference WHERE" + where, String.class, tenant, user);
        var areas = new ArrayList<Area>();
        for (String ref : refs) {
            String[] parts = ref.split(":", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{6}")) return new Ceiling(true, List.of());
            areas.add(new Area(parts[0], parts[1]));
        }
        return new Ceiling(true, List.copyOf(areas));
    }
}
