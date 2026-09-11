package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 拜访证据质量和追加事件仓储，所有读取、更新和幂等键均包含服务端租户。 */
@Repository
class TemporaryCheckinEvidenceRepository {
    private final JdbcTemplate jdbc;
    TemporaryCheckinEvidenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void recordLocation(UUID tenant, UUID submission, String quality, String rawTimestamp,
            Instant clientReceivedAt, String source, String evidenceJson,
            BigDecimal storeLongitude, BigDecimal storeLatitude, BigDecimal distance, Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET location_quality=?, location_raw_timestamp=?,
                    location_received_at=?, location_client_received_at=?, location_source=?,
                    location_evidence_json=CAST(? AS JSON), store_longitude_snapshot=?,
                    store_latitude_snapshot=?, distance_meters=?
                WHERE tenant_id=? AND id=?
                """, quality, rawTimestamp, ts(now), ts(clientReceivedAt), source, evidenceJson,
                storeLongitude, storeLatitude, distance, bin(tenant), bin(submission));
    }

    EvidenceView evidence(UUID tenant, UUID submission) {
        return jdbc.queryForObject("""
                SELECT location_quality,location_raw_timestamp,location_received_at,location_source,
                    store_longitude_snapshot,store_latitude_snapshot,distance_meters,
                    review_status,reviewed_by,reviewed_at,location_address
                FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, (rs,n) -> new EvidenceView(rs.getString(1),rs.getString(2),instant(rs.getTimestamp(3)),
                rs.getString(4),rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),
                rs.getString(8),rs.getString(9),instant(rs.getTimestamp(10)),rs.getString(11)),bin(tenant),bin(submission));
    }

    java.util.Map<UUID,EvidenceView> evidenceBatch(UUID tenant,List<UUID> submissions) {
        java.util.Map<UUID,EvidenceView> result=new java.util.HashMap<>();
        for(int offset=0;offset<submissions.size();offset+=500) {
            List<UUID> part=submissions.subList(offset,Math.min(offset+500,submissions.size()));
            List<Object> arguments=new java.util.ArrayList<>();arguments.add(bin(tenant));
            part.forEach(id->arguments.add(bin(id)));
            String sql="SELECT id,location_quality,location_raw_timestamp,location_received_at,location_source,"
                    +"store_longitude_snapshot,store_latitude_snapshot,distance_meters,review_status,reviewed_by,reviewed_at,location_address "
                    +"FROM temp_sales_checkin_submission WHERE tenant_id=? AND id IN ("
                    +String.join(",",java.util.Collections.nCopies(part.size(),"?"))+")";
            jdbc.query(sql,rs->{result.put(SalesUuidCodec.decode(rs.getBytes(1)),new EvidenceView(rs.getString(2),
                    rs.getString(3),instant(rs.getTimestamp(4)),rs.getString(5),rs.getBigDecimal(6),rs.getBigDecimal(7),
                    rs.getBigDecimal(8),rs.getString(9),rs.getString(10),instant(rs.getTimestamp(11)),rs.getString(12)));},arguments.toArray());
        }
        return result;
    }

    List<ReviewEvent> reviews(UUID tenant, UUID submission) {
        return jdbc.query("""
                SELECT id,status,note,actor,occurred_at FROM temp_sales_checkin_evidence_event
                WHERE tenant_id=? AND submission_id=? AND event_type='REVIEW'
                ORDER BY occurred_at DESC,id DESC
                """, (rs,n) -> new ReviewEvent(SalesUuidCodec.decode(rs.getBytes(1)),rs.getString(2),
                rs.getString(3),rs.getString(4),instant(rs.getTimestamp(5))),bin(tenant),bin(submission));
    }

    ReviewEvent findReview(UUID tenant, UUID submission, UUID event) {
        return jdbc.query("""
                SELECT id,status,note,actor,occurred_at FROM temp_sales_checkin_evidence_event
                WHERE tenant_id=? AND submission_id=? AND client_event_id=? AND event_type='REVIEW'
                """, (rs,n) -> new ReviewEvent(SalesUuidCodec.decode(rs.getBytes(1)),rs.getString(2),
                rs.getString(3),rs.getString(4),instant(rs.getTimestamp(5))),bin(tenant),bin(submission),bin(event))
                .stream().findFirst().orElse(null);
    }

    ReviewEvent review(UUID tenant, UUID submission, UUID event, String status, String note,
            String actor, Instant now) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_evidence_event
                (id,tenant_id,submission_id,client_event_id,event_type,status,note,actor,occurred_at)
                VALUES (?,?,?,?,'REVIEW',?,?,?,?)
                """,bin(id),bin(tenant),bin(submission),bin(event),status,note,actor,ts(now));
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET review_status=?,reviewed_by=?,reviewed_at=?
                WHERE tenant_id=? AND id=?
                """,status,actor,ts(now),bin(tenant),bin(submission));
        return new ReviewEvent(id,status,note,actor,now);
    }

    void supplement(UUID tenant, UUID submission, UUID mediaId, String actor,
            String objectKey, String sha256, Instant now) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_evidence_event
                (id,tenant_id,submission_id,client_event_id,event_type,actor,object_key,sha256,occurred_at)
                VALUES (?,?,?,?,'SUPPLEMENT',?,?,?,?)
                """,bin(UUID.randomUUID()),bin(tenant),bin(submission),bin(mediaId),actor,objectKey,sha256,ts(now));
    }

    List<UUID> ownSubmissionIds(UUID tenant, UUID salesperson, Instant from, Instant toExclusive,
            String status, String sortDirection, int offset, int limit) {
        List<Object> arguments = new java.util.ArrayList<>(List.of(bin(tenant), bin(salesperson)));
        String filter = ownFilter(from, toExclusive, status, arguments);
        String direction = "asc".equals(sortDirection) ? " ASC" : " DESC";
        arguments.add(limit); arguments.add(offset);
        return jdbc.query("SELECT id FROM temp_sales_checkin_submission" + filter
                + " ORDER BY COALESCE(submitted_at,created_at)" + direction + ",id" + direction + " LIMIT ? OFFSET ?",
                (rs,n)->SalesUuidCodec.decode(rs.getBytes(1)),arguments.toArray());
    }

    long ownSubmissionCount(UUID tenant, UUID salesperson, Instant from, Instant toExclusive, String status) {
        List<Object> arguments = new java.util.ArrayList<>(List.of(bin(tenant), bin(salesperson)));
        return jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_submission"
                + ownFilter(from, toExclusive, status, arguments), Long.class, arguments.toArray());
    }

    /** 日期只筛选已提交事实；无日期和状态时保留旧客户端读取服务端草稿的能力。 */
    private static String ownFilter(Instant from, Instant toExclusive, String status, List<Object> arguments) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id=? AND salesperson_id=? AND deletion_state='NONE'");
        if (status != null) { sql.append(" AND status=?"); arguments.add(status); }
        if (from != null || toExclusive != null) sql.append(" AND status='SUBMITTED'");
        if (from != null) { sql.append(" AND submitted_at>=?"); arguments.add(ts(from)); }
        if (toExclusive != null) { sql.append(" AND submitted_at<?"); arguments.add(ts(toExclusive)); }
        return sql.toString();
    }

    private static byte[] bin(UUID id) { return id == null ? null : SalesUuidCodec.encode(id); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    record EvidenceView(String locationQuality,String locationRawTimestamp,Instant locationReceivedAt,
            String locationSource,BigDecimal storeLongitude,BigDecimal storeLatitude,BigDecimal distanceMeters,
            String reviewStatus,String reviewedBy,Instant reviewedAt,String locationAddress) { }
    record ReviewEvent(UUID id,String status,String note,String reviewedBy,Instant reviewedAt) { }
}
