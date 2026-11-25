package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;

import com.rigour.shared.core.exception.StateConflictException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 仅供已核实为供应链专用的租户身份使用，调用方持有同一事务。 */
final class SupplyAccountDeletion {
    private static final String MEMBER = " WHERE tenant_id=:tenant AND user_id=:user";
    private static final String SESSIONS =
            "SELECT id FROM iam_auth_session WHERE tenant_id=:tenant AND principal_scope='TENANT' AND principal_id=:user";
    // 早期注册的三个供应链客户端没有 application_id。未知客户端按其他应用处理。
    private static final String OTHER_CLIENT = """
            (c.application_id<>:app OR (c.application_id IS NULL AND c.client_id NOT IN
            ('rigour-scdp-browser','rigour-scdp-desktop','rigour-sales-app-android')))
            """;

    static void delete(JdbcTemplate jdbc, UUID tenantId, UUID appId, UUID userId) {
        var db = new NamedParameterJdbcTemplate(jdbc);
        var args = Map.of("tenant", bin(tenantId), "user", bin(userId), "app", bin(appId), "subject", userId.toString());
        db.queryForObject("SELECT id FROM iam_user WHERE tenant_id=:tenant AND id=:user FOR UPDATE", args, byte[].class);
        db.queryForList(SESSIONS + " FOR UPDATE", args, byte[].class);
        for (String check : List.of(
                "SELECT 1 FROM iam_app_member" + MEMBER + " AND application_id<>:app AND deleted_at IS NULL",
                "SELECT 1 FROM iam_user_role" + MEMBER + " AND status='ACTIVE' AND (effective_to IS NULL OR effective_to>UTC_TIMESTAMP(6))",
                "SELECT 1 FROM iam_staff_user_binding" + MEMBER + " AND status='ACTIVE' AND deleted_at IS NULL",
                "SELECT 1 FROM iam_oauth_consent a JOIN iam_oauth_client c ON c.id=a.client_id WHERE a.principal_name=:subject AND a.revoked_at IS NULL AND " + OTHER_CLIENT,
                "SELECT 1 FROM iam_oauth_authorization a JOIN iam_oauth_client c ON c.id=a.client_id JOIN iam_auth_session s ON s.id=a.session_id WHERE s.tenant_id=:tenant AND s.principal_id=:user AND s.principal_scope='TENANT' AND s.status='ACTIVE' AND s.expires_at>UTC_TIMESTAMP(6) AND a.status='ACTIVE' AND " + OTHER_CLIENT)) {
            if (!db.queryForList(check + " LIMIT 1 FOR UPDATE", args, Integer.class).isEmpty()) {
                throw new StateConflictException("该账号还有其他应用或平台授权，无法从供应链删除统一登录账号");
            }
        }
        // 换绑历史的外键依赖成员行，删除前将有效区间、原因及原操作人归入应用审计。
        db.update("""
                INSERT INTO iam_app_audit (id,tenant_id,application_id,actor_id,action_code,target_id,result,summary,occurred_at)
                SELECT id,tenant_id,application_id,actor_id,'MEMBER_BINDING_HISTORY',:subject,'SUCCESS',
                       CONCAT(employee_code,' ',effective_from,' 至 ',COALESCE(effective_to,UTC_TIMESTAMP(6)),' ',reason),effective_from
                FROM iam_app_employee_binding_history WHERE tenant_id=:tenant AND user_id=:user
                """, args);
        // 同一次更新解开刷新令牌的轮换链，满足外键及 consumed_at 的成对约束。
        String tokens = " WHERE session_id IN (" + SESSIONS + ")";
        db.update("UPDATE iam_refresh_token SET replaced_by_id=NULL,consumed_at=NULL" + tokens + " AND replaced_by_id IS NOT NULL", args);
        db.update("DELETE FROM iam_refresh_token" + tokens, args);
        db.update("DELETE FROM iam_oauth_authorization" + tokens, args);
        db.update("DELETE FROM iam_oauth_consent WHERE principal_name=:subject", args);
        db.update("DELETE FROM iam_auth_session WHERE tenant_id=:tenant AND principal_id=:user AND principal_scope='TENANT'", args);
        for (String table : List.of("iam_app_member_role_scope", "iam_app_member_role", "iam_app_member_limit_reference",
                "iam_app_member_scope_limit", "iam_app_employee_binding", "iam_app_employee_binding_history",
                "iam_app_authorization_observation", "iam_app_data_observation", "iam_app_member", "iam_external_identity",
                "iam_staff_user_binding", "iam_user_role", "iam_user_organization", "iam_user_credential")) {
            db.update("DELETE FROM " + table + MEMBER, args);
        }
        if (db.update("DELETE FROM iam_user WHERE tenant_id=:tenant AND id=:user", args) != 1) {
            throw new StateConflictException("登录账号已变更，请刷新");
        }
    }

    private SupplyAccountDeletion() {}
}
