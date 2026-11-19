package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.ReceivablesView;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

@Service
public class ReceivablesArchive {
    public record Revision(String version, Instant archivedAt) {}

    private final JdbcTemplate jdbc;
    private final BiDataScopeService scopes;
    private final ReceivablesService reports;
    private final ObjectMapper json;

    public ReceivablesArchive(
            JdbcTemplate jdbc,
            BiDataScopeService scopes,
            ReceivablesService reports,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.reports = reports;
        this.json = json;
    }

    private String tenant() {
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        var s = scopes.resolve(null, null);
        if (!s.fullTenant() || s.regionCode() != null || s.ownerStaffCode() != null)
            throw new AuthorizationDeniedException("analytics:dashboard:read");
        return s.tenantId();
    }

    public List<Revision> revisions(LocalDate from, LocalDate asOf) {
        return jdbc.query(
                "SELECT version_key,archived_at FROM bi_receivable_archive WHERE tenant_id=? AND"
                    + " period_start=? AND as_of_date=? ORDER BY archived_at DESC",
                (r, n) -> new Revision(r.getString(1), r.getTimestamp(2).toInstant()),
                tenant(),
                from,
                asOf);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String save(LocalDate from, LocalDate asOf) throws IOException {
        var tenant = tenant();
        AuthorizationContext.requirePermission("analytics:receivables:archive");
        scopes.requireFullAction("analytics:receivables:archive");
        var r = reports.calculate(from, asOf, null, null, null);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(bytes)) {
            json.writeValue(zip, r);
        }
        jdbc.update(
                """
INSERT INTO bi_receivable_archive(tenant_id,period_start,as_of_date,version_key,snapshot,archived_by) VALUES(?,?,?,?,?,?)
ON DUPLICATE KEY UPDATE version_key=bi_receivable_archive.version_key
""",
                tenant,
                from,
                asOf,
                r.version(),
                bytes.toByteArray(),
                AuthorizationContext.requireCurrent().principalId().toString());
        return r.version();
    }

    public ReceivablesView read(
            LocalDate from,
            LocalDate asOf,
            String version,
            String aging,
            int page,
            int size,
            String collector,
            Boolean disputed)
            throws IOException {
        var payloads =
                jdbc.query(
                        "SELECT snapshot FROM bi_receivable_archive WHERE tenant_id=? AND"
                            + " period_start=? AND as_of_date=? AND version_key=?",
                        (r, n) -> r.getBytes(1),
                        tenant(),
                        from,
                        asOf,
                        version);
        if (payloads.isEmpty())
            throw new BusinessException(ErrorCode.NOT_FOUND, "所选期间没有这个归档版本", List.of());
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(payloads.getFirst()))) {
            return ReceivablesService.page(
                    json.readValue(zip, ReceivablesView.class),
                    aging,
                    page,
                    size,
                    collector,
                    disputed);
        }
    }
}
