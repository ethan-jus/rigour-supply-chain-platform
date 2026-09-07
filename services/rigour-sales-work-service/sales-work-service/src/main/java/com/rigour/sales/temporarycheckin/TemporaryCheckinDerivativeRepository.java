package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 原媒体版本绑定的派生任务仓储；任务领取为短更新，不在转码期间持业务行锁。 */
@Repository
class TemporaryCheckinDerivativeRepository {
    private static final String SOURCE_ACTIVE = """
            EXISTS (SELECT 1 FROM temp_sales_checkin_submission s
            WHERE s.tenant_id=d.tenant_id AND s.id=d.submission_id AND s.deletion_state='NONE' AND (
            ((d.media_id='storefront-photo' OR (d.media_id=CONCAT('photo-',LOWER(BIN_TO_UUID(s.id)))
                AND NOT EXISTS(SELECT 1 FROM temp_sales_checkin_photo oldp WHERE oldp.tenant_id=s.tenant_id AND oldp.submission_id=s.id)))
                AND s.storefront_photo_object_key=d.source_object_key
                AND s.storefront_photo_sha256=d.source_sha256 AND s.storefront_photo_deleted_at IS NULL)
            OR (d.media_id='wechat-screenshot' AND s.wechat_screenshot_object_key=d.source_object_key
                AND s.wechat_screenshot_sha256=d.source_sha256 AND s.wechat_screenshot_deleted_at IS NULL)
            OR EXISTS (SELECT 1 FROM temp_sales_checkin_photo p WHERE p.tenant_id=s.tenant_id
                AND p.submission_id=s.id AND d.media_id=CONCAT('photo-',LOWER(BIN_TO_UUID(p.photo_id)))
                AND p.object_key=d.source_object_key AND p.sha256=d.source_sha256 AND p.deleted_at IS NULL)
            OR (d.kind='AUDIO' AND ((s.audio_segments_json IS NULL OR JSON_LENGTH(s.audio_segments_json)=0)
                AND s.audio_object_key=d.source_object_key AND s.audio_sha256=d.source_sha256
                AND s.audio_deleted_at IS NULL OR EXISTS (
                    SELECT 1 FROM JSON_TABLE(COALESCE(s.audio_segments_json,JSON_ARRAY()),'$[*]'
                    COLUMNS(object_key VARCHAR(1024) PATH '$.objectKey',sha VARCHAR(64) PATH '$.sha256',
                    deleted_at VARCHAR(64) PATH '$.deletedAt' NULL ON EMPTY)) j
                    WHERE j.object_key=d.source_object_key AND j.sha=d.source_sha256 AND j.deleted_at IS NULL)))))
            """;
    private final JdbcTemplate jdbc;
    TemporaryCheckinDerivativeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void enqueue(UUID tenant,UUID submission,String mediaId,String kind,String key,String sha,
            String type,String filename,long bytes,Instant now) {
        if (key == null || sha == null) return;
        jdbc.update("""
                INSERT IGNORE INTO temp_sales_checkin_media_derivative
                (id,tenant_id,submission_id,media_id,kind,source_object_key,source_sha256,
                 source_content_type,source_filename,source_size_bytes,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,bin(UUID.randomUUID()),bin(tenant),bin(submission),mediaId,kind,key,sha,type,filename,
                bytes,Timestamp.from(now),Timestamp.from(now));
    }

    Derivative find(UUID tenant,UUID submission,String mediaId,String sha) {
        return jdbc.query("""
                SELECT * FROM temp_sales_checkin_media_derivative
                WHERE tenant_id=? AND submission_id=? AND media_id=? AND source_sha256=?
                """,(rs,n)->read(rs),bin(tenant),bin(submission),mediaId,sha).stream().findFirst().orElse(null);
    }

    java.util.Map<String,Derivative> forSubmissions(UUID tenant,List<UUID> submissions) {
        if(submissions.isEmpty()) return java.util.Map.of();
        List<Object> arguments=new java.util.ArrayList<>();arguments.add(bin(tenant));
        submissions.forEach(id->arguments.add(bin(id)));
        return jdbc.query("SELECT * FROM temp_sales_checkin_media_derivative WHERE tenant_id=? AND kind='AUDIO' AND submission_id IN ("
                +String.join(",",java.util.Collections.nCopies(submissions.size(),"?"))+")",
                (rs,n)->read(rs),arguments.toArray()).stream().collect(java.util.stream.Collectors.toMap(
                    item->item.submissionId()+"/"+item.mediaId()+"/"+item.sha(),item->item));
    }

    /** 逐批发现历史媒体和未排队上传，避免在后台只读列表事务中写任务。 */
    void discover(UUID tenant,Instant now) {
        var photos=jdbc.query("""
                SELECT p.submission_id,CONCAT('photo-',LOWER(BIN_TO_UUID(p.photo_id))),p.object_key,
                       p.sha256,p.content_type,p.original_filename,p.size_bytes
                FROM temp_sales_checkin_photo p JOIN temp_sales_checkin_submission s
                    ON s.tenant_id=p.tenant_id AND s.id=p.submission_id
                WHERE p.tenant_id=? AND s.deletion_state='NONE' AND p.deleted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM temp_sales_checkin_media_derivative d WHERE d.tenant_id=p.tenant_id
                    AND d.submission_id=p.submission_id AND d.media_id=CONCAT('photo-',LOWER(BIN_TO_UUID(p.photo_id)))
                    AND d.source_sha256=p.sha256) LIMIT 5
                """,(rs,n)->new Source(SalesUuidCodec.decode(rs.getBytes(1)),rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7)),bin(tenant));
        for(var photo:photos) enqueue(tenant,photo.submissionId(),photo.mediaId(),"IMAGE",photo.key(),photo.sha(),
                photo.type(),photo.filename(),photo.bytes(),now);
        for (String prefix:List.of("storefront_photo_","wechat_screenshot_","audio_")) {
            String mediaId="audio_".equals(prefix)?null:("storefront_photo_".equals(prefix)?"storefront-photo":"wechat-screenshot");
            String legacy="audio_".equals(prefix)?" AND (s.audio_segments_json IS NULL OR JSON_LENGTH(s.audio_segments_json)=0)":"";
            String identity="audio_".equals(prefix)?"LOWER(BIN_TO_UUID(s.id))":"'"+mediaId+"'";
            String sql="SELECT s.id,s."+prefix+"object_key,s."+prefix+"sha256,s."+prefix+"content_type,s."
                    +prefix+"original_filename,s."+prefix+"size_bytes FROM temp_sales_checkin_submission s "
                    +"WHERE s.tenant_id=? AND s.deletion_state='NONE' AND s."+prefix+"object_key IS NOT NULL "
                    +"AND s."+prefix+"deleted_at IS NULL AND s."+prefix+"sha256 IS NOT NULL"+legacy
                    +" AND NOT EXISTS (SELECT 1 FROM temp_sales_checkin_media_derivative d WHERE d.tenant_id=s.tenant_id "
                    +"AND d.submission_id=s.id AND d.media_id="+identity+" AND d.source_sha256=s."+prefix+"sha256) LIMIT 5";
            var sources=jdbc.query(sql,(rs,n)->new Source(SalesUuidCodec.decode(rs.getBytes(1)),
                    null,rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)),bin(tenant));
            for(var item:sources) enqueue(tenant,item.submissionId(),mediaId==null?item.submissionId().toString():mediaId,
                    mediaId==null?"AUDIO":"IMAGE",item.key(),item.sha(),item.type(),item.filename(),item.bytes(),now);
        }
        var segments=jdbc.query("""
                SELECT s.id,j.segment_id,j.object_key,j.sha,j.content_type,j.filename,j.size_bytes
                FROM temp_sales_checkin_submission s JOIN JSON_TABLE(COALESCE(s.audio_segments_json,JSON_ARRAY()),'$[*]'
                COLUMNS(segment_id VARCHAR(64) PATH '$.segmentId',object_key VARCHAR(1024) PATH '$.objectKey',
                sha VARCHAR(64) PATH '$.sha256',content_type VARCHAR(128) PATH '$.contentType',
                filename VARCHAR(256) PATH '$.originalFilename',size_bytes BIGINT PATH '$.sizeBytes',
                deleted_at VARCHAR(64) PATH '$.deletedAt' NULL ON EMPTY)) j
                WHERE s.tenant_id=? AND s.deletion_state='NONE' AND j.deleted_at IS NULL AND j.object_key IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM temp_sales_checkin_media_derivative d WHERE d.tenant_id=s.tenant_id
                    AND d.submission_id=s.id AND d.media_id=j.segment_id AND d.source_sha256=j.sha) LIMIT 5
                """,(rs,n)->new Source(SalesUuidCodec.decode(rs.getBytes(1)),rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7)),bin(tenant));
        for(var item:segments) enqueue(tenant,item.submissionId(),item.mediaId(),"AUDIO",item.key(),item.sha(),
                item.type(),item.filename(),item.bytes(),now);
    }

    private record Source(UUID submissionId,String mediaId,String key,String sha,String type,String filename,long bytes) { }

    List<Derivative> obsolete(UUID tenant) {
        return jdbc.query("SELECT d.* FROM temp_sales_checkin_media_derivative d WHERE d.tenant_id=? AND NOT "
                + SOURCE_ACTIVE + " LIMIT 5",(rs,n)->read(rs),bin(tenant));
    }

    void remove(UUID tenant,UUID id) {
        jdbc.update("DELETE FROM temp_sales_checkin_media_derivative WHERE tenant_id=? AND id=?",bin(tenant),bin(id));
    }

    List<Derivative> next(UUID tenant,Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_media_derivative SET status=IF(attempts>=3,'FAILED','PENDING'),
                    error_code='WORKER_INTERRUPTED',lease_token=NULL,updated_at=?
                WHERE tenant_id=? AND status='PROCESSING' AND updated_at<?
                """,Timestamp.from(now),bin(tenant),Timestamp.from(now.minusSeconds(180)));
        return jdbc.query("""
                SELECT * FROM temp_sales_checkin_media_derivative
                WHERE tenant_id=? AND status='PENDING' AND attempts<3 ORDER BY created_at,id LIMIT 1
                """,(rs,n)->read(rs),bin(tenant));
    }

    boolean claim(UUID tenant,UUID id,UUID lease,Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_media_derivative SET status='PROCESSING',attempts=attempts+1,lease_token=?,updated_at=?
                WHERE tenant_id=? AND id=? AND status='PENDING' AND attempts<3
                """,bin(lease),Timestamp.from(now),bin(tenant),bin(id))==1;
    }

    boolean success(UUID tenant,UUID id,UUID lease,Long duration,byte[] thumbnail,String key,Long size,Instant now) {
        return jdbc.update("""
                UPDATE temp_sales_checkin_media_derivative d SET status='READY',duration_ms=?,thumbnail_bytes=?,
                    derived_object_key=?,derived_size_bytes=?,error_code=NULL,updated_at=?
                WHERE tenant_id=? AND id=? AND status='PROCESSING' AND lease_token=?
                """ + " AND " + SOURCE_ACTIVE,duration,thumbnail,key,size,Timestamp.from(now),bin(tenant),bin(id),bin(lease))==1;
    }

    void failed(UUID tenant,UUID id,UUID lease,String code,Long duration,Instant now) {
        jdbc.update("""
                UPDATE temp_sales_checkin_media_derivative SET status='FAILED',error_code=?,duration_ms=?,updated_at=?
                WHERE tenant_id=? AND id=? AND status='PROCESSING' AND lease_token=?
                """,code,duration,Timestamp.from(now),bin(tenant),bin(id),bin(lease));
    }

    private static Derivative read(ResultSet rs) throws SQLException {
        return new Derivative(SalesUuidCodec.decode(rs.getBytes("id")),
                SalesUuidCodec.decode(rs.getBytes("submission_id")),rs.getString("media_id"),
                rs.getString("kind"),rs.getString("source_object_key"),rs.getString("source_sha256"),
                rs.getString("source_content_type"),rs.getString("source_filename"),rs.getLong("source_size_bytes"),
                rs.getString("status"), nullableLong(rs,"duration_ms"),rs.getBytes("thumbnail_bytes"),
                rs.getString("derived_object_key"),nullableLong(rs,"derived_size_bytes"),rs.getString("error_code"));
    }
    private static Long nullableLong(ResultSet rs,String name) throws SQLException {
        long value=rs.getLong(name);return rs.wasNull()?null:value;
    }
    private static byte[] bin(UUID id) { return SalesUuidCodec.encode(id); }
    record Derivative(UUID id,UUID submissionId,String mediaId,String kind,String sourceKey,String sha,
            String sourceType,String filename,long sourceBytes,String status,Long durationMs,
            byte[] thumbnail,String derivedKey,Long derivedBytes,String errorCode) { }
}
