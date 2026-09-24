package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.application.port.out.LoginTenantQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoginTenantQuery implements LoginTenantQuery {
    private final JdbcTemplate jdbc;

    public JdbcLoginTenantQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public String name(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) return null;
        return jdbc.queryForList("SELECT company_name FROM iam_tenant WHERE tenant_code=? AND status='ACTIVE' AND deleted_at IS NULL",
                String.class, tenantCode).stream().findFirst().orElse(null);
    }
}
