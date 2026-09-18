package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 保存地址来源和租户内坐标缓存。短事务申领/完成，网络解析不占有数据库事务或行锁。 */
@Repository
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinAddressRepository {
    static final String CONVERSION_VERSION = "LOCAL_WGS84_GCJ02_V1";
    static final String PROVIDER_VERSION = "AMAP_REGEO_ALL_R1000_" + CONVERSION_VERSION;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    TemporaryCheckinAddressRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }

    AddressRow find(UUID tenant, UUID id, String city) {
        String sql = """
                SELECT id,city,longitude,latitude,COALESCE(NULLIF(TRIM(location_formatted_address),''),location_address) location_address,location_formatted_address,
                    address_source,address_resolved_at,address_conversion_version,address_coordinate_hash
                  FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND deletion_state='NONE'
                """ + (city == null ? "" : " AND city=?");
        Object[] arguments = city == null ? new Object[]{bin(tenant),bin(id)} : new Object[]{bin(tenant),bin(id),city};
        return jdbc.query(sql,(rs,n) -> new AddressRow(SalesUuidCodec.decode(rs.getBytes(1)),rs.getString(2),
                rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5),rs.getString(6),rs.getString(7),
                instant(rs.getTimestamp(8)),rs.getString(9),rs.getString(10)),arguments).stream().findFirst().orElse(null);
    }

    void recordCaptureSnapshot(UUID tenant, UUID id, Instant now) {
        AddressRow row = find(tenant,id,null);
        if (row == null || row.longitude() == null || row.latitude() == null) return;
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET address_source='CAPTURE_SNAPSHOT',
                    address_resolved_at=?,address_conversion_version=?,address_coordinate_hash=?
                 WHERE tenant_id=? AND id=? AND deletion_state='NONE' AND geocode_status='RESOLVED'
                   AND NULLIF(TRIM(location_address),'') IS NOT NULL
                """,ts(now),CONVERSION_VERSION,coordinateHash(row.longitude(),row.latitude()),bin(tenant),bin(id));
        // 正常请求已获得的服务器地址也可供后来显式补解析复用，不重复购买同坐标解析。
        // 不覆盖已有在途任务或已保存答案，避免与管理员租约竞争。
        jdbc.update("""
                INSERT INTO temp_sales_checkin_address_cache
                    (tenant_id,coordinate_hash,provider_version,longitude,latitude,status,result_json,resolved_at,attempts,updated_at)
                SELECT tenant_id,address_coordinate_hash,?,longitude,latitude,'RESOLVED',
                    JSON_OBJECT('status','RESOLVED','address',location_address,'formattedAddress',location_formatted_address,
                        'adcode',location_adcode,'province',location_province,'city',location_city,'district',location_district,
                        'township',location_township,'amapLongitude',amap_longitude,'amapLatitude',amap_latitude,'errorCode',NULL),
                    ?,0,?
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=? AND deletion_state='NONE'
                    AND address_coordinate_hash IS NOT NULL AND geocode_status='RESOLVED'
                ON DUPLICATE KEY UPDATE coordinate_hash=VALUES(coordinate_hash)
                """,PROVIDER_VERSION,ts(now),ts(now),bin(tenant),bin(id));
    }

    CacheRow cache(UUID tenant, String key) {
        return jdbc.query("""
                SELECT status,result_json,resolved_at,retry_at,lease_until
                  FROM temp_sales_checkin_address_cache
                 WHERE tenant_id=? AND coordinate_hash=? AND provider_version=?
                """,(rs,n) -> new CacheRow(rs.getString(1),rs.getString(2),instant(rs.getTimestamp(3)),
                instant(rs.getTimestamp(4)),instant(rs.getTimestamp(5))),bin(tenant),key,PROVIDER_VERSION)
                .stream().findFirst().orElse(null);
    }

    Claim claim(UUID tenant, AddressRow row, String key, String actor, LocalDate day,
            Instant now, int dailyLimit, Instant leaseUntil) {
        return transactions.execute(ignored -> {
            jdbc.update("""
                    INSERT INTO temp_sales_checkin_address_guard(tenant_id,budget_date,calls_used)
                    VALUES (?,?,0) ON DUPLICATE KEY UPDATE tenant_id=tenant_id
                    """,bin(tenant),java.sql.Date.valueOf(day));
            Guard guard = jdbc.queryForObject("""
                    SELECT budget_date,calls_used,lease_until FROM temp_sales_checkin_address_guard
                    WHERE tenant_id=? FOR UPDATE
                    """,(rs,n) -> new Guard(rs.getDate(1).toLocalDate(),rs.getInt(2),instant(rs.getTimestamp(3))),bin(tenant));
            CacheRow cached = cache(tenant,key);
            if (cached != null && "RESOLVED".equals(cached.status())) return new Claim("RESOLVED",null,cached,null);
            if (cached != null && cached.retryAt() != null && cached.retryAt().isAfter(now))
                return new Claim("RETRY_LATER",null,cached,cached.retryAt());
            if (guard.leaseUntil() != null && guard.leaseUntil().isAfter(now))
                return new Claim("PROCESSING",null,cached,guard.leaseUntil());
            int used = day.equals(guard.day()) ? guard.used() : 0;
            if (used >= dailyLimit) return new Claim("QUOTA_EXCEEDED",null,cached,null);
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE temp_sales_checkin_address_guard SET budget_date=?,calls_used=?,lease_token=?,lease_until=?
                    WHERE tenant_id=?
                    """,java.sql.Date.valueOf(day),used+1,bin(token),ts(leaseUntil),bin(tenant));
            jdbc.update("""
                    INSERT INTO temp_sales_checkin_address_cache
                        (tenant_id,coordinate_hash,provider_version,longitude,latitude,status,claim_token,
                         lease_until,attempts,requested_by,updated_at)
                    VALUES (?,?,?,?,?,'PROCESSING',?,?,1,?,?)
                    ON DUPLICATE KEY UPDATE status='PROCESSING',claim_token=?,lease_until=?,
                        attempts=attempts+1,requested_by=?,updated_at=?,retry_at=NULL
                    """,bin(tenant),key,PROVIDER_VERSION,row.longitude(),row.latitude(),bin(token),ts(leaseUntil),actor,ts(now),
                    bin(token),ts(leaseUntil),actor,ts(now));
            return new Claim("CLAIMED",token,null,null);
        });
    }

    boolean finish(UUID tenant, String key, UUID token, String resultJson, boolean success,
            Instant now, Instant retryAt) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> {
            // 先锁租户 guard，和 claim 的锁顺序一致；失去租约的旧请求不能覆盖新结果。
            UUID current = jdbc.queryForObject("SELECT lease_token FROM temp_sales_checkin_address_guard WHERE tenant_id=? FOR UPDATE",
                    (rs,n) -> rs.getBytes(1) == null ? null : SalesUuidCodec.decode(rs.getBytes(1)),bin(tenant));
            if (!token.equals(current)) return false;
            int updated = jdbc.update("""
                    UPDATE temp_sales_checkin_address_cache SET status=?,result_json=CAST(? AS JSON),
                        resolved_at=?,retry_at=?,claim_token=NULL,lease_until=NULL,updated_at=?
                     WHERE tenant_id=? AND coordinate_hash=? AND provider_version=? AND claim_token=? AND status='PROCESSING'
                    """,success ? "RESOLVED" : "FAILED",resultJson,success ? ts(now) : null,
                    success ? null : ts(retryAt),ts(now),bin(tenant),key,PROVIDER_VERSION,bin(token));
            jdbc.update("UPDATE temp_sales_checkin_address_guard SET lease_token=NULL,lease_until=NULL WHERE tenant_id=? AND lease_token=?",
                    bin(tenant),bin(token));
            return updated == 1;
        }));
    }

    int apply(UUID tenant, AddressRow row, String key, TemporaryCheckinReverseGeocoder.GeocodeResult result,
            Instant resolvedAt, Instant appliedAt) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_submission SET location_address=?,location_formatted_address=?,
                    location_adcode=?,location_province=?,location_city=?,location_district=?,location_township=?,
                    amap_longitude=?,amap_latitude=?,geocode_status='RESOLVED',geocode_error_code=NULL,geocoded_at=?,
                    address_source='HISTORICAL_COORDINATES_RESOLVED_LATER',address_resolved_at=?,
                    address_conversion_version=?,address_coordinate_hash=?,
                    updated_at=GREATEST(?,TIMESTAMPADD(MICROSECOND,1,updated_at))
                 WHERE tenant_id=? AND id=? AND city=? AND deletion_state='NONE'
                   AND longitude=? AND latitude=? AND NULLIF(TRIM(location_address),'') IS NULL
                   AND NULLIF(TRIM(location_formatted_address),'') IS NULL
                """,result.address(),result.formattedAddress(),result.adcode(),result.province(),result.city(),
                result.district(),result.township(),result.amapLongitude(),result.amapLatitude(),ts(resolvedAt),
                ts(resolvedAt),CONVERSION_VERSION,key,ts(appliedAt),bin(tenant),bin(row.id()),row.city(),row.longitude(),row.latitude());
    }

    static String coordinateHash(BigDecimal longitude, BigDecimal latitude) {
        String canonical = "WGS84|" + longitude.setScale(7,RoundingMode.HALF_UP).toPlainString()
                + "|" + latitude.setScale(7,RoundingMode.HALF_UP).toPlainString();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static byte[] bin(UUID id) { return SalesUuidCodec.encode(id); }
    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    record AddressRow(UUID id,String city,BigDecimal longitude,BigDecimal latitude,String address,String formattedAddress,
            String source,Instant resolvedAt,String conversionVersion,String coordinateHash) { }
    record CacheRow(String status,String resultJson,Instant resolvedAt,Instant retryAt,Instant leaseUntil) { }
    record Claim(String status,UUID token,CacheRow cached,Instant retryAt) { }
    private record Guard(LocalDate day,int used,Instant leaseUntil) { }
}
