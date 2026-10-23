package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentView;
import com.rigour.hr.application.port.out.PayrollPaymentAttachmentStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class JdbcPayrollPaymentAttachmentStore implements PayrollPaymentAttachmentStore {
    private final JdbcTemplate jdbc;
    private final HrPayrollPersistenceSupport audit;

    public JdbcPayrollPaymentAttachmentStore(JdbcTemplate jdbc, HrPayrollPersistenceSupport audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public LockedPayroll lock(String tenant, String payrollId) {
        var rows = jdbc.query("SELECT revision,status_code FROM hr_payroll WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs, n) -> new LockedPayroll(rs.getInt("revision"), rs.getString("status_code")), tenant, payrollId);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    public List<PayrollPaymentAttachmentView> listForUpdate(String tenant, String payrollId, String paymentId) {
        // Current read is essential after waiting for the payroll lock under MySQL REPEATABLE READ.
        return jdbc.query("SELECT * FROM hr_payroll_payment_attachment WHERE tenant_id=? AND payroll_id=?"
                + " AND payment_id=? ORDER BY created_time,id FOR UPDATE", (rs, n) -> metadata(rs), tenant, payrollId, paymentId);
    }

    public List<PayrollPaymentAttachmentView> list(String tenant, String payrollId, String paymentId) {
        return jdbc.query("SELECT * FROM hr_payroll_payment_attachment WHERE tenant_id=? AND payroll_id=?"
                + " AND payment_id=? ORDER BY created_time,id", (rs, n) -> metadata(rs), tenant, payrollId, paymentId);
    }

    public Map<String, List<PayrollPaymentAttachmentView>> listByPayroll(String tenant, String payrollId) {
        Map<String, List<PayrollPaymentAttachmentView>> result = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM hr_payroll_payment_attachment WHERE tenant_id=? AND payroll_id=?"
                + " ORDER BY created_time,id", rs -> {
                    result.computeIfAbsent(rs.getString("payment_id"), key -> new ArrayList<>()).add(metadata(rs));
                }, tenant, payrollId);
        return result;
    }

    public Stored file(String tenant, String payrollId, String paymentId, String attachmentId) {
        var rows = jdbc.query("SELECT * FROM hr_payroll_payment_attachment WHERE tenant_id=? AND payroll_id=?"
                + " AND payment_id=? AND id=?", (rs, n) -> new Stored(metadata(rs), rs.getString("file_key")),
                tenant, payrollId, paymentId, attachmentId);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    public void add(String tenant, String payrollId, String paymentId, PayrollPaymentAttachmentView file, String key) {
        jdbc.update("""
                INSERT INTO hr_payroll_payment_attachment(id,tenant_id,payroll_id,payment_id,file_key,file_name,
                    content_type,file_size,created_by,created_by_name,created_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, file.id(), tenant, payrollId, paymentId, key, file.fileName(), file.contentType(), file.size(),
                file.createdBy(), file.createdByName(), LocalDateTime.ofInstant(file.createdTime(), ZoneOffset.UTC));
        audit.audit(tenant, payrollId, "PAYMENT_ATTACHMENT_ADD", file.createdBy(),
                "补充发放凭证：" + file.fileName(), file);
    }

    private static PayrollPaymentAttachmentView metadata(ResultSet row) throws SQLException {
        return new PayrollPaymentAttachmentView(row.getString("id"), row.getString("file_name"),
                row.getString("content_type"), row.getLong("file_size"), row.getString("created_by"),
                row.getString("created_by_name"), row.getObject("created_time", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static BusinessException missing() {
        return new BusinessException(ErrorCode.NOT_FOUND, "发放凭证不存在", List.of());
    }
}
