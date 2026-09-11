package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只从租户内活动事实构造关联；登记表提供稳定编号，不保存 Cookie，不物化容易过期的拜访数量。 */
@Repository
public class TemporaryCheckinRiskRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private static final String VISIBLE="s.tenant_id=:tenant AND s.status='SUBMITTED' AND s.deletion_state='NONE'"
            +" AND (:scope IS NULL OR s.city=:scope)";
    private static final String FILTER="(:from IS NULL OR s.submitted_at>=:from) AND (:until IS NULL OR s.submitted_at<:until)"
            +" AND (:city IS NULL OR s.city=:city) AND (:sales IS NULL OR s.salesperson_id=:sales)";
    private static final String DEVICE_JOIN="temp_sales_checkin_submission s JOIN temp_sales_checkin_risk_device g"
            +" ON g.tenant_id=s.tenant_id AND g.token_hash=s.device_token_hash AND g.hash_version='V1'";
    private static final String AUDIO_JOIN="temp_sales_checkin_risk_audio_source a JOIN temp_sales_checkin_submission s"
            +" ON s.tenant_id=a.tenant_id AND s.id=a.submission_id JOIN temp_sales_checkin_risk_audio g"
            +" ON g.tenant_id=a.tenant_id AND g.sha256=a.sha256 AND g.size_bytes=a.size_bytes";

    public TemporaryCheckinRiskRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);
    }

    /** 应在后台控制器进入既有 readOnly 服务前调用；失败不会进入销售写入链路。 */
    public void ensureRegistry(UUID tenant,Instant now) {
        jdbc.update("""
                INSERT IGNORE INTO temp_sales_checkin_risk_device(tenant_id,token_hash,created_at)
                SELECT s.tenant_id,s.device_token_hash,? FROM temp_sales_checkin_submission s
                WHERE s.tenant_id=? AND s.status='SUBMITTED' AND s.deletion_state='NONE'
                  AND s.device_token_hash IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM temp_sales_checkin_risk_device d WHERE d.tenant_id=s.tenant_id
                    AND d.token_hash=s.device_token_hash AND d.hash_version='V1')
                GROUP BY s.tenant_id,s.device_token_hash ORDER BY s.device_token_hash
                """,utc(now),bin(tenant));
        jdbc.update("""
                INSERT IGNORE INTO temp_sales_checkin_risk_audio(tenant_id,sha256,size_bytes,created_at)
                SELECT a.tenant_id,a.sha256,a.size_bytes,? FROM temp_sales_checkin_risk_audio_source a
                WHERE a.tenant_id=? AND NOT EXISTS(SELECT 1 FROM temp_sales_checkin_risk_audio g
                    WHERE g.tenant_id=a.tenant_id AND g.sha256=a.sha256 AND g.size_bytes=a.size_bytes)
                GROUP BY a.tenant_id,a.sha256,a.size_bytes ORDER BY a.sha256,a.size_bytes
                """,utc(now),bin(tenant));
    }

    List<GroupKey> groupsForSubmissions(UUID tenant,String scope,List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        Map<String,Object> args=arguments(tenant,scope,null);args.put("ids",ids.stream().map(TemporaryCheckinRiskRepository::bin).toList());
        String sql="SELECT DISTINCT 'DEVICE' kind,g.id FROM "+DEVICE_JOIN+" WHERE "+VISIBLE+" AND s.id IN (:ids)"
                +" UNION ALL SELECT DISTINCT 'AUDIO',g.id FROM "+AUDIO_JOIN+" WHERE "+VISIBLE+" AND s.id IN (:ids)";
        return named.query(sql,args,(rs,n)->new GroupKey(rs.getString(1),rs.getLong(2)));
    }

    List<UUID> visibleIds(UUID tenant,String scope,List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        var args=arguments(tenant,scope,null);args.put("ids",ids.stream().map(TemporaryCheckinRiskRepository::bin).toList());
        return named.query("SELECT s.id FROM temp_sales_checkin_submission s WHERE "+VISIBLE+" AND s.id IN (:ids)",
                args,(rs,n)->uuid(rs,"id"));
    }

    Map<UUID,String> oldRiskLevels(UUID tenant,String scope,List<UUID> ids) {
        if(ids.isEmpty())return Map.of();
        var args=arguments(tenant,scope,null);args.put("ids",ids.stream().map(TemporaryCheckinRiskRepository::bin).toList());
        Map<UUID,String> values=new HashMap<>();
        named.query("SELECT s.id,s.risk_level FROM temp_sales_checkin_submission s WHERE "+VISIBLE+" AND s.id IN (:ids)",args,
                (org.springframework.jdbc.core.RowCallbackHandler)rs->values.put(uuid(rs,"id"),rs.getString("risk_level")));
        return values;
    }

    GroupPage groupPage(UUID tenant,String scope,String kind,Filters filters,String query,boolean duplicate,
            boolean crossSales,String reviewStatus,String order,String direction,int page,int size) {
        var args=arguments(tenant,scope,filters);args.put("query",query==null?null:"%"+escape(query)+"%");
        args.put("kind",kind);args.put("scopeKey",scopeKey(scope));args.put("rules",TemporaryCheckinRiskModels.RULES_VERSION);args.put("reviewStatus",reviewStatus);
        String memberKey=kind.equals("DEVICE")?"LOWER(HEX(s.id))":"CONCAT(LOWER(HEX(s.id)),':',LOWER(a.segment_id))";
        String reviewJoin=reviewStatus==null?"":"""
                 LEFT JOIN (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY group_id ORDER BY reviewed_at DESC,id DESC) seq
                    FROM temp_sales_checkin_risk_review r WHERE r.tenant_id=:tenant AND r.group_kind=:kind AND r.scope_key=:scopeKey) rv
                    ON rv.group_id=g.id AND rv.seq=1
                 LEFT JOIN temp_sales_checkin_risk_review_member rm ON rm.tenant_id=rv.tenant_id AND rm.review_id=rv.id AND rm.member_key=
                """+memberKey;
        String code="CONCAT('"+(kind.equals("DEVICE")?"DEV":"AUD")+"-',LPAD(g.id,GREATEST(6,LENGTH(g.id)),'0'))";
        String inner="SELECT g.id,COUNT(DISTINCT s.id) visit_count,COUNT(DISTINCT s.salesperson_id) salesperson_count,"
                +"MAX(s.submitted_at) last_submitted FROM "+(kind.equals("DEVICE")?DEVICE_JOIN:AUDIO_JOIN)+reviewJoin
                +" WHERE "+VISIBLE+" AND (:query IS NULL OR "+code+" LIKE :query ESCAPE '=') GROUP BY g.id"
                +" HAVING SUM(CASE WHEN "+FILTER+" THEN 1 ELSE 0 END)>0"
                +(duplicate?" AND COUNT(DISTINCT s.id)>1":"")
                +(crossSales?" AND COUNT(DISTINCT s.salesperson_id)>1":"")
                +(reviewStatus==null?"":" AND (CASE WHEN "+(kind.equals("DEVICE")?"COUNT(DISTINCT s.salesperson_id)":"COUNT(DISTINCT s.id)")
                    +"<=1 THEN 'NOT_REQUIRED' WHEN MAX(rv.id) IS NULL OR MAX(rv.rules_version)<>:rules"
                    +" OR MAX(rv.member_count)<>COUNT(DISTINCT "+memberKey+") OR SUM(CASE WHEN rm.member_key IS NULL THEN 1 ELSE 0 END)>0"
                    +" THEN 'PENDING' ELSE MAX(rv.status) END)=:reviewStatus");
        long total=named.queryForObject("SELECT COUNT(*) FROM ("+inner+") risk_groups",args,Long.class);
        String column=switch(order) {case "historyCount"->"visit_count";case "salespersonCount"->"salesperson_count";default->"last_submitted";};
        args.put("limit",size);args.put("offset",(long)page*size);
        List<Long> ids=named.query("SELECT id FROM ("+inner+") risk_groups ORDER BY "+column+" "+direction
                +",id "+direction+" LIMIT :limit OFFSET :offset",args,(rs,n)->rs.getLong(1));
        return new GroupPage(ids,total);
    }

    List<Member> members(UUID tenant,String scope,String kind,List<Long> groupIds) {
        if(groupIds.isEmpty()) return List.of();
        var args=arguments(tenant,scope,null);args.put("groups",groupIds);
        String audio=kind.equals("AUDIO")
                ?"a.segment_id,a.sha256,a.size_bytes,a.original_filename,a.uploaded_at_text,a.client_duration_ms,a.capture_source,d.duration_ms,d.status derived_status"
                :"NULL segment_id,NULL sha256,NULL size_bytes,NULL original_filename,NULL uploaded_at_text,NULL client_duration_ms,NULL capture_source,NULL duration_ms,NULL derived_status";
        String sql="SELECT DISTINCT g.id group_id,s.id,s.submitted_at,s.city,s.salesperson_id,s.salesperson_name_snapshot,"
                +"s.store_id,s.store_name_snapshot,COALESCE(NULLIF(s.location_formatted_address,''),s.location_address) location_address,"
                +"dev.id device_id,s.user_agent_summary,s.risk_level,"+audio+" FROM "
                +(kind.equals("DEVICE")?DEVICE_JOIN:AUDIO_JOIN)
                +" LEFT JOIN temp_sales_checkin_risk_device dev ON dev.tenant_id=s.tenant_id AND dev.token_hash=s.device_token_hash AND dev.hash_version='V1'"
                +(kind.equals("AUDIO")?" LEFT JOIN temp_sales_checkin_media_derivative d ON d.tenant_id=a.tenant_id AND d.submission_id=a.submission_id"
                    +" AND d.media_id=a.segment_id AND d.source_sha256=a.sha256 AND d.kind='AUDIO'":"")
                +" WHERE "+VISIBLE+" AND g.id IN (:groups) ORDER BY s.submitted_at,s.id LIMIT 100001";
        var rows=named.query(sql,args,(rs,n)->new Member(kind,rs.getLong("group_id"),uuid(rs,"id"),instant(rs,"submitted_at"),
                rs.getString("city"),uuid(rs,"salesperson_id"),rs.getString("salesperson_name_snapshot"),uuid(rs,"store_id"),
                rs.getString("store_name_snapshot"),rs.getString("location_address"),nullableLong(rs,"device_id"),
                rs.getString("user_agent_summary"),rs.getString("risk_level"),rs.getString("segment_id"),rs.getString("sha256"),
                nullableLong(rs,"size_bytes"),rs.getString("original_filename"),rs.getString("uploaded_at_text"),
                positive(nullableLong(rs,"client_duration_ms")),rs.getString("capture_source"),positive(nullableLong(rs,"duration_ms")),rs.getString("derived_status")));
        if(rows.size()>100000)throw TemporaryCheckinException.badRequest("关联证据超过100000项，请缩小查询或导出范围");
        return rows;
    }

    List<Member> audioForVisits(UUID tenant,String scope,List<UUID> ids) {
        var groups=groupsForSubmissions(tenant,scope,ids).stream().filter(k->k.kind().equals("AUDIO")).map(GroupKey::id).distinct().toList();
        if(groups.isEmpty()) return List.of();
        // 当前页至多一百条；按组取出的其他拜访立即丢弃，不把隐藏行交给调用方。
        var wanted=new java.util.HashSet<>(ids);
        return members(tenant,scope,"AUDIO",groups).stream().filter(m->wanted.contains(m.submissionId())).toList();
    }

    Map<Long,StoredReview> latestReviews(UUID tenant,String scope,String kind,List<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        var args=arguments(tenant,scope,null);args.put("kind",kind);args.put("groups",ids);args.put("scopeKey",scopeKey(scope));
        var rows=named.query("""
                SELECT * FROM (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY group_id ORDER BY reviewed_at DESC,id DESC) seq
                    FROM temp_sales_checkin_risk_review r WHERE tenant_id=:tenant AND group_kind=:kind
                    AND group_id IN (:groups) AND scope_key=:scopeKey) ordered WHERE seq=1
                """,args,(rs,n)->readReview(rs));
        Map<Long,StoredReview> out=new HashMap<>();rows.forEach(row->out.put(row.groupId(),row));return out;
    }

    StoredReview reviewByClient(UUID tenant,UUID event) {
        return jdbc.query("SELECT * FROM temp_sales_checkin_risk_review WHERE tenant_id=? AND client_event_id=?",
                (rs,n)->readReview(rs),bin(tenant),bin(event)).stream().findFirst().orElse(null);
    }
    StoredAssignment assignmentByClient(UUID tenant,UUID event) {
        return jdbc.query("SELECT * FROM temp_sales_checkin_risk_assignment WHERE tenant_id=? AND client_event_id=?",
                (rs,n)->readAssignment(rs),bin(tenant),bin(event)).stream().findFirst().orElse(null);
    }
    void lockGroup(UUID tenant,String kind,long id) {
        String table=kind.equals("DEVICE")?"temp_sales_checkin_risk_device":"temp_sales_checkin_risk_audio";
        if(jdbc.query("SELECT id FROM "+table+" WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getLong(1),bin(tenant),id).isEmpty())
            throw TemporaryCheckinException.notFound("关联档案不存在");
    }
    void insertReview(UUID tenant,String kind,long group,String scope,ReviewEvent event,String requestHash,List<String> members) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_risk_review(id,tenant_id,group_kind,group_id,scope_key,client_event_id,
                    evidence_version,rules_version,status,note,actor,reviewed_at,member_count,request_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,bin(event.id()),bin(tenant),kind,group,scopeKey(scope),bin(event.clientEventId()),event.evidenceVersion(),
                event.rulesVersion(),event.status(),event.note(),event.actor(),utc(event.reviewedAt()),members.size(),requestHash);
        jdbc.batchUpdate("INSERT INTO temp_sales_checkin_risk_review_member(tenant_id,review_id,member_key) VALUES(?,?,?)",
                members,250,(ps,key)->{ps.setBytes(1,bin(tenant));ps.setBytes(2,bin(event.id()));ps.setString(3,key);});
    }
    void insertAssignment(UUID tenant,long device,String scope,AssignmentEvent event,String requestHash) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_risk_assignment(id,tenant_id,device_id,client_event_id,scope_key,
                    assignment_type,salesperson_id,salesperson_name,valid_from,valid_to,note,actor,assigned_at,request_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,bin(event.id()),bin(tenant),device,bin(event.clientEventId()),scopeKey(scope),event.assignmentType(),
                bin(event.salespersonId()),event.salespersonName(),event.validFrom(),event.validTo(),event.note(),event.actor(),utc(event.assignedAt()),requestHash);
    }
    String salespersonName(UUID tenant,String scope,UUID id) {
        return jdbc.query("SELECT name FROM temp_sales_checkin_salesperson WHERE tenant_id=? AND id=? AND status='ACTIVE'"
                +" AND (? IS NULL OR city=?)",(rs,n)->rs.getString(1),bin(tenant),bin(id),scope,scope).stream().findFirst()
                .orElseThrow(()->TemporaryCheckinException.notFound("可指定的销售不存在"));
    }
    Page<ReviewEvent> reviews(UUID tenant,String scope,String kind,long group,int page,int size) {
        Object[] args={bin(tenant),kind,group,scopeKey(scope)};
        String where=" WHERE tenant_id=? AND group_kind=? AND group_id=? AND scope_key=?";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_review"+where,Long.class,args);
        var values=new ArrayList<Object>(List.of(args));values.add(size);values.add((long)page*size);
        var items=jdbc.query("SELECT * FROM temp_sales_checkin_risk_review"+where+" ORDER BY reviewed_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n)->readReview(rs).event(),values.toArray());
        return page(items,page,size,total);
    }
    Page<AssignmentEvent> assignments(UUID tenant,String scope,long group,int page,int size) {
        var values=new ArrayList<Object>();values.add(bin(tenant));values.add(group);
        String where=" WHERE tenant_id=? AND device_id=?";
        if(scope!=null) {where+=" AND scope_key=?";values.add(scopeKey(scope));}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_risk_assignment"+where,Long.class,values.toArray());
        values.add(size);values.add((long)page*size);
        var items=jdbc.query("SELECT * FROM temp_sales_checkin_risk_assignment"+where+" ORDER BY assigned_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n)->readAssignment(rs).event(),values.toArray());
        return page(items,page,size,total);
    }
    Map<Long,AssignmentEvent> latestAssignments(UUID tenant,String scope,List<Long> groups) {
        if(groups.isEmpty())return Map.of();
        var args=arguments(tenant,scope,null);args.put("groups",groups);args.put("scopeKey",scopeKey(scope));
        var rows=named.query("""
                SELECT * FROM (SELECT a.*,ROW_NUMBER() OVER(PARTITION BY device_id ORDER BY assigned_at DESC,id DESC) seq
                  FROM temp_sales_checkin_risk_assignment a WHERE tenant_id=:tenant AND device_id IN (:groups)
                """+(scope==null?"":" AND scope_key=:scopeKey")+") latest WHERE seq=1",args,(rs,n)->readAssignment(rs));
        Map<Long,AssignmentEvent> result=new HashMap<>();rows.forEach(row->result.put(row.deviceId(),row.event()));return result;
    }
    IdentityEventPage identityEvents(UUID tenant,String scope,long device,boolean changesOnly,int page,int size) {
        var args=arguments(tenant,scope,null);args.put("device",device);args.put("limit",size);args.put("offset",(long)page*size);
        String visible="""
                SELECT e.* FROM temp_sales_checkin_identity_event e JOIN temp_sales_checkin_risk_device d
                  ON d.tenant_id=e.tenant_id AND d.token_hash=e.device_token_hash AND d.hash_version='V1'
                WHERE e.tenant_id=:tenant AND d.id=:device AND (:scope IS NULL OR e.identity_city=:scope)
                """;
        var coverage=named.queryForObject("SELECT COUNT(*) available,MIN(occurred_at) first_at FROM ("+visible+") visible",args,
                (rs,n)->new IdentityCoverage(rs.getLong("available"),instant(rs,"first_at")));
        String ordered="WITH visible AS ("+visible+"""
                ), ordered AS (SELECT v.*,LAG(id) OVER chronological AS previous_id,
                    LAG(salesperson_id) OVER chronological AS previous_salesperson_id,
                    LAG(salesperson_name_snapshot) OVER chronological AS previous_name,
                    LAG(occurred_at) OVER chronological AS previous_at
                  FROM visible v WINDOW chronological AS (ORDER BY occurred_at,id))
                """;
        String filter=changesOnly?" WHERE previous_salesperson_id IS NOT NULL AND previous_salesperson_id<>salesperson_id":"";
        long total=named.queryForObject(ordered+" SELECT COUNT(*) FROM ordered"+filter,args,Long.class);
        var items=named.query(ordered+" SELECT * FROM ordered"+filter+" ORDER BY occurred_at DESC,id DESC LIMIT :limit OFFSET :offset",args,(rs,n)->{
            UUID previous=uuid(rs,"previous_salesperson_id"),current=uuid(rs,"salesperson_id");
            Long previousId=nullableLong(rs,"previous_id");
            return new IdentityEvent(Long.toString(rs.getLong("id")),instant(rs,"occurred_at"),current,rs.getString("salesperson_name_snapshot"),
                    rs.getString("identity_city"),previous!=null&&!previous.equals(current)?"VISIBLE_ACCOUNT_CHANGED":"IDENTITY_VERIFIED",
                    previousId==null?null:previousId.toString(),previous,rs.getString("previous_name"),instant(rs,"previous_at"));
        });
        return new IdentityEventPage(List.copyOf(items),page,size,total,(int)Math.min(Integer.MAX_VALUE,total==0?0:(total-1)/size+1),
                coverage.available(),coverage.firstAt(),false);
    }
    private record IdentityCoverage(long available,Instant firstAt) { }

    private static StoredReview readReview(ResultSet rs) throws SQLException {
        String scope=rs.getString("scope_key");
        var event=new ReviewEvent(uuid(rs,"id"),uuid(rs,"client_event_id"),rs.getString("status"),rs.getString("note"),
                rs.getString("actor"),instant(rs,"reviewed_at"),rs.getString("evidence_version"),rs.getString("rules_version"),
                rs.getLong("member_count"),scopeCity(scope));
        return new StoredReview(rs.getString("group_kind"),rs.getLong("group_id"),scope,rs.getString("request_hash"),event);
    }
    private static StoredAssignment readAssignment(ResultSet rs) throws SQLException {
        String scope=rs.getString("scope_key");
        var event=new AssignmentEvent(uuid(rs,"id"),uuid(rs,"client_event_id"),rs.getString("assignment_type"),
                uuid(rs,"salesperson_id"),rs.getString("salesperson_name"),rs.getObject("valid_from",LocalDate.class),
                rs.getObject("valid_to",LocalDate.class),rs.getString("note"),rs.getString("actor"),instant(rs,"assigned_at"),scopeCity(scope));
        return new StoredAssignment(rs.getLong("device_id"),scope,rs.getString("request_hash"),event);
    }
    private static Map<String,Object> arguments(UUID tenant,String scope,Filters filters) {
        Map<String,Object> args=new HashMap<>();args.put("tenant",bin(tenant));args.put("scope",scope);
        args.put("from",filters==null?null:utc(filters.from()));args.put("until",filters==null?null:utc(filters.until()));
        args.put("city",filters==null?null:filters.city());args.put("sales",filters==null?null:bin(filters.salespersonId()));return args;
    }
    static <T> Page<T> page(List<T> items,int page,int size,long total) {
        return new Page<>(List.copyOf(items),page,size,total,(int)Math.min(Integer.MAX_VALUE,total==0?0:(total-1)/size+1));
    }
    static String scopeKey(String city) {return city==null?"ALL":"CITY:"+city;}
    static String scopeCity(String key) {return "ALL".equals(key)?null:key.substring(5);}
    static String code(String kind,long id) {return (kind.equals("DEVICE")?"DEV-":"AUD-")+String.format(java.util.Locale.ROOT,"%06d",id);}
    static byte[] bin(UUID id) {return id==null?null:SalesUuidCodec.encode(id);}
    private static UUID uuid(ResultSet rs,String col) throws SQLException {byte[] bytes=rs.getBytes(col);return bytes==null?null:SalesUuidCodec.decode(bytes);}
    private static Long nullableLong(ResultSet rs,String col) throws SQLException {long x=rs.getLong(col);return rs.wasNull()?null:x;}
    private static Long positive(Long value) {return value!=null&&value>0?value:null;}
    private static Instant instant(ResultSet rs,String col) throws SQLException {var value=rs.getObject(col,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static LocalDateTime utc(Instant value) {return value==null?null:LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static String escape(String s) {return s.replace("=","==").replace("%","=%").replace("_","=_");}

    record GroupKey(String kind,long id) { }
    record GroupPage(List<Long> ids,long total) { }
    record StoredReview(String kind,long groupId,String scopeKey,String requestHash,ReviewEvent event) { }
    record StoredAssignment(long deviceId,String scopeKey,String requestHash,AssignmentEvent event) { }
    record Member(String kind,long groupId,UUID submissionId,Instant submittedAt,String city,UUID salespersonId,
            String salespersonName,UUID storeId,String storeName,String locationAddress,Long deviceId,String userAgent,
            String oldRiskLevel,String segmentId,String sha256,Long sizeBytes,String originalFilename,String uploadedAtText,
            Long clientDurationMs,String captureSource,Long parsedDurationMs,String playbackStatus) {
        String memberKey() {return submissionId.toString().replace("-","")+("AUDIO".equals(kind)?":"+segmentId:"");}
    }
}
