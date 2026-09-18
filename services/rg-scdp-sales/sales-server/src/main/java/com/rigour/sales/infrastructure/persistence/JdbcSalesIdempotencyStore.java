package com.rigour.sales.infrastructure.persistence;

import com.rigour.shared.idempotency.IdempotencyKey;
import com.rigour.shared.idempotency.IdempotencyRecord;
import com.rigour.shared.idempotency.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sales Work 幂等实现；请求摘要和结果引用落在本服务 Schema，不保存定位正文。 */
@Repository
public class JdbcSalesIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcSalesIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Reservation reserveCommand(UUID tenantId, UUID principalId, String operation,
                                      String key, String requestHash, Duration ttl) {
        Timestamp expiresAt = Timestamp.from(java.time.Instant.now().plus(ttl));
        jdbc.update("""
                DELETE FROM sales_idempotency_record
                 WHERE tenant_id=? AND idempotency_key=? AND expires_at <= UTC_TIMESTAMP(6)
                """, bin(tenantId), key);
        try {
            jdbc.update("""
                    INSERT INTO sales_idempotency_record
                        (tenant_id, idempotency_key, operation_type, principal_id, request_hash,
                         result_status, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, bin(tenantId), key, operation, bin(principalId), requestHash, expiresAt);
            return new Reservation(Status.RESERVED, null);
        } catch (DuplicateKeyException duplicate) {
            List<ExistingRecord> rows = jdbc.query("""
                    SELECT request_hash, result_status, result_reference
                      FROM sales_idempotency_record
                     WHERE tenant_id=? AND idempotency_key=?
                    """, (rs, row) -> new ExistingRecord(rs.getString("request_hash"),
                    rs.getString("result_status"), rs.getString("result_reference")),
                    bin(tenantId), key);
            if (rows.isEmpty()) {
                return new Reservation(Status.IN_PROGRESS, null);
            }
            ExistingRecord existing = rows.getFirst();
            if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                return new Reservation(Status.CONFLICT, null);
            }
            return "COMPLETED".equals(existing.status())
                    ? new Reservation(Status.COMPLETED, existing.reference())
                    : new Reservation(Status.IN_PROGRESS, null);
        }
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKey key) {
        List<IdempotencyRecord> rows = jdbc.query("""
                SELECT operation_type, result_status, result_reference, expires_at
                  FROM sales_idempotency_record
                 WHERE tenant_id=? AND idempotency_key=?
                """, (rs, row) -> new IdempotencyRecord(
                new IdempotencyKey(key.tenantId(), rs.getString("operation_type"), key.value()),
                "COMPLETED".equals(rs.getString("result_status"))
                        ? IdempotencyRecord.Status.COMPLETED : IdempotencyRecord.Status.PROCESSING,
                rs.getString("result_reference"),
                rs.getTimestamp("expires_at").toInstant().atOffset(ZoneOffset.UTC)),
                bin(UUID.fromString(key.tenantId())), key.value());
        return rows.stream().findFirst();
    }

    @Override
    public boolean reserve(IdempotencyKey key, Duration timeToLive) {
        Reservation reservation = reserveCommand(UUID.fromString(key.tenantId()),
                UUID.fromString(key.tenantId()), key.operation(), key.value(), "", timeToLive);
        return reservation.status() == Status.RESERVED;
    }

    @Override
    public void complete(IdempotencyKey key, String responsePayload) {
        jdbc.update("""
                UPDATE sales_idempotency_record
                   SET result_status='COMPLETED', result_reference=?, response_code='OK',
                       updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND idempotency_key=? AND result_status='PROCESSING'
                """, responsePayload, bin(UUID.fromString(key.tenantId())), key.value());
    }

    @Override
    public void release(IdempotencyKey key) {
        jdbc.update("""
                DELETE FROM sales_idempotency_record
                 WHERE tenant_id=? AND idempotency_key=? AND result_status='PROCESSING'
                """, bin(UUID.fromString(key.tenantId())), key.value());
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }

    public enum Status {
        RESERVED, COMPLETED, IN_PROGRESS, CONFLICT
    }

    public record Reservation(Status status, String reference) {
    }

    private record ExistingRecord(String requestHash, String status, String reference) {
    }
}
