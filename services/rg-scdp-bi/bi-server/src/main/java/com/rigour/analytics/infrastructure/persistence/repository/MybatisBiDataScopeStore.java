package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.infrastructure.persistence.mapper.BiDataScopeMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 范围投影、撤销及 CAS 续期适配器，仅写 BI 自有数据。 */
@Repository
public class MybatisBiDataScopeStore implements BiDataScopeStore {
    private final BiDataScopeMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public boolean appObjectVisible(String tenant, String city, String employee, String action) {
        var p = com.rigour.tenant.iam.client.SupplyAuthorizationContext.requireAction(action);
        if (p == null) return false;
        var predicate =
                com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.predicate(
                        p, "OBJECT");
        var args = new java.util.ArrayList<Object>();
        args.add(city);
        args.add(employee);
        args.add(tenant);
        args.addAll(predicate.args());
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT ? AS city_code, ? AS employee_code) f LEFT"
                                + " JOIN bi_region_authority a ON a.tenant_id=? AND"
                                + " a.region_code=f.city_code WHERE "
                                + predicate.text(),
                        Integer.class,
                        args.toArray())
                == 1;
    }

    @Override
    public void compareObject(
            String tenant, String city, String employee, String action, boolean oldAllowed) {
        String key =
                java.util.HexFormat.of()
                        .formatHex(
                                digest(
                                        Objects.toString(city, "")
                                                + "|"
                                                + Objects.toString(employee, "")));
        com.rigour.tenant.iam.client.SupplyAuthorizationContext.compare(
                action,
                "BI",
                key,
                oldAllowed,
                p -> {
                    var predicate =
                            com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates
                                    .predicate(p, "OBJECT");
                    var args = new java.util.ArrayList<Object>();
                    args.add(city);
                    args.add(employee);
                    args.add(tenant);
                    args.addAll(predicate.args());
                    return jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM (SELECT ? AS city_code,? AS"
                                        + " employee_code) f LEFT JOIN bi_region_authority a ON"
                                        + " a.tenant_id=? AND a.region_code=f.city_code WHERE "
                                            + predicate.text(),
                                    Integer.class,
                                    args.toArray())
                            == 1;
                });
    }

    private static byte[] digest(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public MybatisBiDataScopeStore(BiDataScopeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Identity> identity(String tenantId, String userId) {
        var row = mapper.identity(tenantId, userId);
        if (row == null || row.isEmpty()) return Optional.empty();
        return Optional.of(
                new Identity(
                        text(row, "employeeCode"),
                        text(row, "ownerStaffCode"),
                        text(row, "iamBindingRef"),
                        text(row, "hrEmployeeRef"),
                        text(row, "crmEmployeeRef"),
                        number(row, "userSecurityVersion"),
                        number(row, "tenantPolicyVersion"),
                        instant(row, "verifiedAt"),
                        instant(row, "expiresAt")));
    }

    @Override
    public List<Grant> grants(String tenantId, String userId) {
        return mapper.grants(tenantId, userId);
    }

    @Override
    @Transactional
    public void replace(
            String tenantId, String userId, Identity identity, List<Grant> grants, String actorId) {
        mapper.deleteGrants(tenantId, userId);
        mapper.deleteIdentity(tenantId, userId);
        var row = new java.util.HashMap<String, Object>();
        row.put("tenantId", tenantId);
        row.put("userId", userId);
        row.put("employeeCode", identity.employeeCode());
        row.put("ownerStaffCode", identity.ownerStaffCode());
        row.put("iamBindingRef", identity.iamBindingRef());
        row.put("hrEmployeeRef", identity.hrEmployeeRef());
        row.put("crmEmployeeRef", identity.crmEmployeeRef());
        row.put("userSecurityVersion", identity.userSecurityVersion());
        row.put("tenantPolicyVersion", identity.tenantPolicyVersion());
        row.put("verifiedAt", LocalDateTime.ofInstant(identity.verifiedAt(), ZoneOffset.UTC));
        row.put("expiresAt", LocalDateTime.ofInstant(identity.expiresAt(), ZoneOffset.UTC));
        mapper.insertIdentity(row);
        grants.stream().distinct().forEach(grant -> mapper.insertGrant(tenantId, userId, grant));
        mapper.audit(tenantId, userId, actorId, "SYNCHRONIZE", "SOURCE_VERIFIED");
    }

    @Override
    @Transactional
    public void revoke(String tenantId, String userId, String actorId, String reason) {
        mapper.deleteGrants(tenantId, userId);
        mapper.deleteIdentity(tenantId, userId);
        mapper.audit(tenantId, userId, actorId, "REVOKE", reason);
    }

    @Override
    @Transactional
    public boolean renewIfUnchanged(
            String tenantId, String userId, Identity previous, Identity refreshed, String actorId) {
        var row = new java.util.HashMap<String, Object>();
        row.put("tenantId", tenantId);
        row.put("userId", userId);
        row.put("employeeCode", previous.employeeCode());
        row.put("ownerStaffCode", previous.ownerStaffCode());
        row.put("userSecurityVersion", previous.userSecurityVersion());
        row.put("tenantPolicyVersion", previous.tenantPolicyVersion());
        row.put(
                "previousVerifiedAt",
                LocalDateTime.ofInstant(previous.verifiedAt(), ZoneOffset.UTC));
        row.put("previousExpiresAt", LocalDateTime.ofInstant(previous.expiresAt(), ZoneOffset.UTC));
        row.put("verifiedAt", LocalDateTime.ofInstant(refreshed.verifiedAt(), ZoneOffset.UTC));
        row.put("expiresAt", LocalDateTime.ofInstant(refreshed.expiresAt(), ZoneOffset.UTC));
        boolean renewed = mapper.renewIfUnchanged(row) == 1;
        if (renewed) mapper.audit(tenantId, userId, actorId, "RENEW", "SOURCE_REVERIFIED");
        return renewed;
    }

    @Override
    public SupplyDashboardFilterOptionsView filterOptions(
            String tenantId, List<String> regions, String owner) {
        if (regions == null) {
            if (com.rigour.analytics.infrastructure.persistence.scope.BiScopePredicates.policy()
                    == null) throw new IllegalArgumentException("需要应用数据范围");
        } else if (regions.isEmpty())
            throw new IllegalArgumentException("At least one authorized region is required");
        var options =
                mapper.filterOptions(tenantId, regions, owner).stream()
                        .map(
                                row ->
                                        new SupplyDashboardFilterOptionView(
                                                text(row, "optionType"),
                                                text(row, "optionValue"),
                                                label(row),
                                                null))
                        .toList();
        return new SupplyDashboardFilterOptionsView(
                ofType(options, "REGION"),
                ofType(options, "SALES_OWNER"),
                ofType(options, "CUSTOMER_TYPE"),
                ofType(options, "PRODUCT_CATEGORY"),
                ofType(options, "SOURCE_SYSTEM"));
    }

    private static String label(Map<String, Object> row) {
        String value = text(row, "optionValue");
        if ("SOURCE_SYSTEM".equals(text(row, "optionType"))) {
            return switch (value) {
                case "FEISHU" -> "飞书";
                case "DINGHUOBAO" -> "订货宝";
                case "MANUAL" -> "手工录入";
                default -> value;
            };
        }
        String label = text(row, "optionLabel");
        return label == null || label.isBlank() ? value : label;
    }

    private static List<SupplyDashboardFilterOptionView> ofType(
            List<SupplyDashboardFilterOptionView> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.optionType())).toList();
    }

    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String text(Map<String, Object> row, String key) {
        var value = value(row, key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        var value = value(row, key);
        return value instanceof Number n ? n.longValue() : -1;
    }

    private static Instant instant(Map<String, Object> row, String key) {
        var value = value(row, key);
        if (value instanceof Instant time) return time;
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp time) return time.toInstant();
        return null;
    }
}
