package com.rigour.settings.infrastructure.persistence.repository;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.settings.application.port.out.AdminDivisionStore;
import com.rigour.shared.core.exception.StateConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAdminDivisionStore implements AdminDivisionStore {
    private final JdbcTemplate jdbc;
    public JdbcAdminDivisionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true)
    public AdminDivisionCatalog published(String requested) {
        var versions = requested == null
                ? jdbc.queryForList("SELECT version_code,source_name,source_ref FROM admin_division_version WHERE status_code='PUBLISHED' AND effective_from<=UTC_DATE() ORDER BY effective_from DESC,version_code DESC LIMIT 1")
                : jdbc.queryForList("SELECT version_code,source_name,source_ref FROM admin_division_version WHERE version_code=? AND status_code IN ('PUBLISHED','RETIRED')", requested);
        if (versions.isEmpty()) throw new StateConflictException("行政区划尚未发布，请先导入并核验行政区划数据");
        var v = versions.getFirst();
        String version = (String) v.get("version_code");
        var nodes = jdbc.query("SELECT admin_code,admin_name,admin_level,parent_code,region_code FROM admin_division WHERE version_code=? AND status_code='ACTIVE' ORDER BY admin_code",
                (rs, n) -> new AdminDivisionCatalog.Node(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), version);
        return new AdminDivisionCatalog(version, (String)v.get("source_name"), (String)v.get("source_ref"), nodes);
    }
}
