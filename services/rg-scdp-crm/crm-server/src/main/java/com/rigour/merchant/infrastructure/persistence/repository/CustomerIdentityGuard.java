package com.rigour.merchant.infrastructure.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Cross-writer identity validation. Existing conflicts may be maintained, never expanded. */
@Component
public final class CustomerIdentityGuard {
    private final JdbcTemplate jdbc;
    public CustomerIdentityGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String conflict(String tenant, Long id, String name, String account, String city, String region) {
        return conflict(tenant, id, name, account, city, region, false);
    }

    public String conflict(String tenant, Long id, String name, String account, String city, String region,
                           boolean allowSameName) {
        lock(tenant);
        var previous = id == null ? java.util.List.<Map<String,Object>>of() : jdbc.queryForList(
                "SELECT customer_name,login_account,city_name,region_code FROM crm_customer WHERE tenant_id=? AND id=? AND deleted=0 FOR UPDATE",tenant,id);
        var old = previous.isEmpty() ? null : previous.getFirst();
        String cityKey = cityKey(tenant,city,region);
        boolean nameChanged = old == null || !normalize(name).equals(normalize(text(old,"customer_name")))
                || !Objects.equals(cityKey,cityKey(tenant,text(old,"city_name"),text(old,"region_code")));
        boolean accountChanged = old == null || !normalize(account).equals(normalize(text(old,"login_account")));
        if (!nameChanged && !accountChanged) return null;
        var candidates = jdbc.queryForList("""
            SELECT id,customer_name,login_account,city_name,region_code FROM crm_customer
            WHERE tenant_id=? AND deleted=0 AND (? IS NULL OR id<>?)
              AND (LOWER(TRIM(customer_name))=? OR (NULLIF(LOWER(TRIM(login_account)),'')=?)) FOR UPDATE
            """,tenant,id,id,normalize(name),normalize(account));
        for (var other : candidates) {
            if (accountChanged && !normalize(account).isEmpty() && normalize(account).equals(normalize(text(other,"login_account"))))
                return "客户账号已被其他客户使用，请使用唯一的客户账号";
            if (!allowSameName && nameChanged && cityKey != null && normalize(name).equals(normalize(text(other,"customer_name")))
                    && cityKey.equals(cityKey(tenant,text(other,"city_name"),text(other,"region_code"))))
                return "同一城市已存在相同客户名称，请核对现有客户";
        }
        // A merged customer's secondary Dinghuobao account is still reserved by that customer.
        if (accountChanged && !normalize(account).isEmpty()) {
            var aliases = jdbc.queryForList("""
                SELECT c.id FROM crm_source_binding b JOIN crm_customer c
                    ON UUID_TO_BIN(c.tenant_id)=b.tenant_id AND c.party_id=b.target_id AND c.deleted=0
                WHERE c.tenant_id=? AND (? IS NULL OR c.id<>?) AND b.deleted=0
                    AND b.source_object_type='CUSTOMER' AND b.binding_status='RESOLVED'
                    AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientAccount'))))=?
                LIMIT 1 FOR UPDATE
                """,tenant,id,id,normalize(account));
            if (!aliases.isEmpty()) return "客户账号已关联现有订货宝客户，请勿重复建档";
        }
        return null;
    }

    public void lock(String tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("客户身份校验必须在保存事务内执行");
        jdbc.update("INSERT INTO crm_customer_identity_guard(tenant_id) VALUES (?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id", tenant);
    }

    public String cityKey(String tenant,String city,String region) {
        String value=normalize(city);
        if (value.isEmpty() && region != null && !region.isBlank()) {
            var areas=jdbc.queryForList("SELECT area_name FROM crm_customer_area WHERE tenant_id=UUID_TO_BIN(?) AND area_code=? AND deleted=0",tenant,region);
            if (!areas.isEmpty()) value=normalize(text(areas.getFirst(),"area_name"));
        }
        // These business buckets are not geographic cities.
        if (value.isEmpty() || java.util.Set.of("全国","总部","散客","大客户","大客户地区").contains(value)) return null;
        if (value.endsWith("省") || value.endsWith("自治区")) return null;
        String[] parts=value.split("[-/\\s]+");
        if (parts.length > 1) {
            value=java.util.Set.of("北京市","上海市","重庆市","天津市").contains(parts[0]) ? parts[0] : parts[1];
        }
        return value.replaceFirst("(?:地区|市)$", "");
    }
    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String text(Map<String,Object> row,String key) { return row.get(key)==null ? null : row.get(key).toString(); }
}
