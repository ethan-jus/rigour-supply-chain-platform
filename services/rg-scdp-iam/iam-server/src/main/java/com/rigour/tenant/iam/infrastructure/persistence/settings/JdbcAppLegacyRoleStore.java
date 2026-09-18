package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.tenant.iam.application.port.out.AppLegacyRoleStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.application.service.settings.AppLegacyRoleModels.*;
import com.rigour.tenant.iam.domain.model.settings.*;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 应用锁和来源指纹保证迁入可复核；只引用当前供应链节点，绝不写原SCDP角色。 */
@Repository
public class JdbcAppLegacyRoleStore implements AppLegacyRoleStore {
    private final JdbcTemplate jdbc;
    private final JdbcAppSettingsStore settings;
    private final JdbcAppRoleStore roles;

    public JdbcAppLegacyRoleStore(
            JdbcTemplate jdbc, JdbcAppSettingsStore settings, JdbcAppRoleStore roles) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.roles = roles;
    }

    private void require(Actor a) {
        settings.requirePermission(a, "supply:role:grant");
        if (!roles.protectedAdministrator(a)) throw new AccessDeniedException("仅受保护管理员可以迁入旧角色");
    }

    @Transactional(readOnly = true)
    public List<Source> sources(Actor a) {
        require(a);
        return read(a);
    }

    private List<Source> read(Actor a) {
        var context = settings.context(a);
        var nodes = settings.rows(a);
        var nodeMap =
                nodes.stream()
                        .collect(java.util.stream.Collectors.toMap(AppMenuTree.Node::id, n -> n));
        var rows =
                jdbc.queryForList(
                        "SELECT id,role_code,role_name,status,version FROM iam_role WHERE"
                            + " tenant_id=? AND deleted_at IS NULL ORDER BY role_name,id",
                        bin(a.tenantId()));
        var result = new ArrayList<Source>();
        for (var row : rows) {
            UUID id = UuidBinaryCodec.decode((byte[]) row.get("id"));
            var resourceIds =
                    jdbc.query(
                            "SELECT DISTINCT res.id FROM iam_effective_tenant_role_resource rr JOIN"
                                + " iam_resource res ON res.id=rr.resource_id JOIN"
                                + " iam_tenant_subscription sub ON sub.tenant_id=rr.tenant_id AND"
                                + " sub.status IN ('ACTIVE','SCHEDULED') AND"
                                + " sub.effective_from<=UTC_TIMESTAMP(6) AND"
                                + " sub.effective_to>UTC_TIMESTAMP(6) JOIN iam_package_resource pr"
                                + " ON pr.package_version_id=sub.package_version_id AND"
                                + " pr.resource_id=res.id WHERE rr.tenant_id=? AND rr.role_id=? AND"
                                + " rr.status='ACTIVE' AND res.application_id=? AND"
                                + " res.status='ACTIVE' AND res.deleted_at IS NULL ORDER BY res.id",
                            (r, n) -> UuidBinaryCodec.decode(r.getBytes(1)),
                            bin(a.tenantId()),
                            bin(id),
                            bin(settings.applicationId()));
            if (resourceIds.isEmpty()) continue;
            var selected =
                    nodes.stream()
                            .filter(
                                    n ->
                                            resourceIds.contains(n.resourceId())
                                                    && AppMenuTree.enabled(n, nodeMap))
                            .toList();
            var ids =
                    selected.stream()
                            .map(AppMenuTree.Node::id)
                            .collect(java.util.stream.Collectors.toSet());
            var permissions =
                    selected.stream()
                            .map(AppMenuTree.Node::permissionCode)
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted()
                            .toList();
            var imported =
                    jdbc.query(
                            "SELECT target_role_id FROM iam_app_legacy_role_mapping WHERE"
                                + " tenant_id=? AND application_id=? AND source_role_id=?",
                            (r, n) -> UuidBinaryCodec.decode(r.getBytes(1)),
                            bin(a.tenantId()),
                            bin(settings.applicationId()),
                            bin(id));
            String fingerprint =
                    hash(
                            id
                                    + "|"
                                    + row.get("version")
                                    + "|"
                                    + row.get("status")
                                    + "|"
                                    + resourceIds
                                    + "|"
                                    + ids.stream().sorted().toList()
                                    + "|"
                                    + permissions);
            result.add(
                    new Source(
                            id,
                            (String) row.get("role_code"),
                            (String) row.get("role_name"),
                            (String) row.get("status"),
                            context.version(),
                            fingerprint,
                            ids,
                            permissions,
                            imported.isEmpty() ? null : imported.getFirst()));
        }
        return List.copyOf(result);
    }

    @Transactional
    public Role importRole(Actor a, UUID id, Command c) {
        require(a);
        settings.lock(a);
        if (!"PREPARING".equals(settings.context(a).mode()))
            throw new IllegalStateException("旧角色迁入仅用于配置准备阶段");
        var source =
                read(a).stream()
                        .filter(s -> s.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("旧供应链角色不存在"));
        if (c == null
                || c.applicationVersion() != source.applicationVersion()
                || !Objects.equals(c.fingerprint(), source.fingerprint()))
            throw new IllegalStateException("来源授权或供应链配置已变化，请重新预览");
        if (source.importedRoleId() != null)
            throw new IllegalStateException("旧角色已迁入，请查看现有供应链角色和来源记录");
        String name = c.name() == null ? source.name() : c.name().strip();
        if (name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("角色名称需为1至128字");
        var rules =
                source.permissions().stream()
                        .filter(p -> AppScopeRules.objectType(p) != null)
                        .map(
                                p ->
                                        new ScopeRule(
                                                null,
                                                p,
                                                AppScopeRules.objectType(p),
                                                "NONE",
                                                "NONE",
                                                "NONE",
                                                "NONE",
                                                false,
                                                Map.of()))
                        .toList();
        var created =
                roles.saveRole(
                        a,
                        null,
                        new RoleCommand(
                                "SCM_IMPORT_"
                                        + UUID.randomUUID()
                                                .toString()
                                                .replace("-", "")
                                                .toUpperCase(Locale.ROOT),
                                name,
                                "来源旧角色：" + source.code() + "；请核对功能并配置数据范围后启用",
                                "DISABLED",
                                0,
                                source.menuNodeIds(),
                                rules));
        String evidence =
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(source);
        jdbc.update(
                "INSERT INTO"
                    + " iam_app_legacy_role_mapping(tenant_id,application_id,source_role_id,target_role_id,source_fingerprint,source_evidence,actor_id)"
                    + " VALUES(?,?,?,?,?,?,?)",
                bin(a.tenantId()),
                bin(settings.applicationId()),
                bin(id),
                bin(created.id()),
                source.fingerprint(),
                evidence,
                bin(a.principalId()));
        settings.audit(
                a,
                "ROLE_IMPORT",
                created.id().toString(),
                "旧角色 " + source.code() + " 迁入为停用角色，数据范围均为无；原角色与成员未变");
        return created;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
