package com.rigour.merchant.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.StorePoolStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStorePoolStore implements StorePoolStore {
    private static final String FROM=" FROM crm_store_summary q JOIN crm_store_master s ON s.tenant_id=q.tenant_id AND s.id=q.store_id LEFT JOIN crm_store_ownership current_o ON current_o.tenant_id=q.tenant_id AND current_o.id=q.current_ownership_id ";
    private static final String ACTIVITY="CASE WHEN q.activity_status='ACTIVE' AND q.activity_expires_at<=UTC_TIMESTAMP(6) THEN CASE WHEN q.order_history_complete_to>=UTC_TIMESTAMP(6) THEN 'DORMANT' ELSE 'UNKNOWN' END ELSE q.activity_status END";
    private final JdbcTemplate jdbc;
    public JdbcStorePoolStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private record Filter(String sql,List<Object> args) {}

    private Filter filter(StoreScope scope,Query query,boolean cursor) {
        return filter(scope,query,cursor,Set.of());
    }
    private Filter filter(StoreScope scope,Query query,boolean cursor,Set<String> typeCodes) {
        var p=StoreScopeSql.compile(scope); var args=new ArrayList<Object>();
        args.add(scope.access().tenantId());args.add(scope.catalog().version());args.addAll(p.args());
        StringBuilder sql=new StringBuilder("q.tenant_id=? AND q.admin_area_version=? AND q.record_status='ACTIVE' AND (").append(p.sql()).append(')');
        switch(query.view()) {
            case "PUBLIC" -> sql.append(" AND q.pool_type='PUBLIC'");
            case "MINE" -> {sql.append(" AND q.pool_type='PRIVATE' AND q.owner_employee_id=?");args.add(scope.access().employeeId());}
            case "TEAM" -> sql.append(" AND q.pool_type='PRIVATE'");
            case "COOPERATED" -> sql.append(" AND q.cooperation_status='COOPERATED'");
            default -> {}
        }
        condition(sql,args,"q.geo_region_code",query.region());condition(sql,args,"q.province_code",query.province());
        condition(sql,args,"q.city_code",query.city());condition(sql,args,"q.county_code",query.county());
        condition(sql,args,"q.cooperation_status",query.cooperation());condition(sql,args,"q.customer_tier",query.tier());
        condition(sql,args,ACTIVITY,query.activity());condition(sql,args,"q.potential_level",query.potential());
        if("__UNCLASSIFIED__".equals(query.storeType()))sql.append(" AND (s.store_type_code IS NULL OR s.store_type_code='')");
        else if(!typeCodes.isEmpty()) {
            sql.append(" AND s.store_type_code IN (").append(String.join(",",Collections.nCopies(typeCodes.size(),"?"))).append(')');
            args.addAll(typeCodes);
        }
        if(query.keyword()!=null&&!query.keyword().isBlank()) {
            sql.append(" AND (LOCATE(?,s.name)>0 OR LOCATE(?,s.address)>0)");args.add(query.keyword().strip());args.add(query.keyword().strip());
        }
        if(cursor&&query.cursor()!=null&&!query.cursor().isBlank()){sql.append(" AND q.store_id<?");args.add(query.cursor());}
        return new Filter(sql.toString(),args);
    }
    private static void condition(StringBuilder sql,List<Object> args,String field,String value) {
        if(value!=null&&!value.isBlank()){sql.append(" AND ").append(field).append("=?");args.add(value);}
    }
    private static Query all() {return new Query("ALL",null,null,null,null,null,null,null,null,null,null,20);}
    private String select(StoreScope scope) {
        // Employee IDs originate exclusively in IAM, but remain bound in queries below.
        return "SELECT s.*,q.current_ownership_id,current_o.hold_reason,current_o.hold_until,q.valid_visit_count,q.pool_type,q.owner_employee_id,q.owner_employee_code,q.cooperation_status,q.customer_tier,q.tier_unrated_reason,"
                + ACTIVITY+" AS effective_activity,q.phase,q.deadline_at,q.last_visit_at,q.last_order_at,q.geo_region_code,q.province_code,q.city_code,q.county_code,q.revision AS summary_revision,"
                + "(SELECT MAX(o.cooldown_until) FROM crm_store_ownership o WHERE o.tenant_id=q.tenant_id AND o.store_id=q.store_id AND o.owner_employee_id=?) AS cooldown_until,"
                + "(SELECT CONVERT(i.external_id USING utf8mb4) FROM crm_store_identity i WHERE i.tenant_id=q.tenant_id AND i.store_id=q.store_id AND i.provider_code='AMAP' AND i.status_code='ACTIVE' ORDER BY i.verified_at DESC,i.id DESC LIMIT 1) AS primary_poi"+FROM;
    }
    @Override
    @Transactional(readOnly=true)
    public Page list(StoreScope scope,Query query,Set<String> typeCodes) {
        var totalFilter=filter(scope,query,false,typeCodes);
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+FROM+" WHERE "+totalFilter.sql(),Long.class,totalFilter.args().toArray()));
        var f=filter(scope,query,true,typeCodes);var args=new ArrayList<Object>();args.add(scope.access().employeeId());args.addAll(f.args());args.add(query.size()+1);
        var rows=jdbc.query(select(scope)+" WHERE "+f.sql()+" ORDER BY q.store_id DESC LIMIT ?",(rs,n)->row(scope,rs),args.toArray());
        boolean more=rows.size()>query.size();var items=more?rows.subList(0,query.size()):rows;
        return new Page(total,List.copyOf(items),more?items.getLast().id():null);
    }
    private static final String LOCATED="s.longitude BETWEEN -180 AND 180 AND s.latitude BETWEEN -90 AND 90 AND s.coordinate_system IN ('GCJ02','WGS84')";
    private record MapStats(long total,long located,MapBounds bounds) {}

    @Override
    @Transactional(readOnly=true)
    public MapResult map(StoreScope scope,Query query,Set<String> typeCodes,MapWindow window) {
        var f=filter(scope,query,false,typeCodes);
        var stats=jdbc.queryForObject("SELECT COUNT(*) AS total,COALESCE(SUM("+LOCATED+"),0) AS located,"
                +"MIN(CASE WHEN "+LOCATED+" THEN s.longitude END) AS west,MAX(CASE WHEN "+LOCATED+" THEN s.longitude END) AS east,"
                +"MIN(CASE WHEN "+LOCATED+" THEN s.latitude END) AS south,MAX(CASE WHEN "+LOCATED+" THEN s.latitude END) AS north"
                +FROM+" WHERE "+f.sql(),(rs,n)->new MapStats(rs.getLong("total"),rs.getLong("located"),rs.getLong("located")==0?null:
                    new MapBounds(rs.getDouble("west"),rs.getDouble("south"),rs.getDouble("east"),rs.getDouble("north"))),f.args().toArray());
        double zoom=window.zoom()==null?4:window.zoom();
        String level=zoom<7?"PROVINCE":zoom<10?"PREFECTURE":zoom<13?"COUNTY":"STORE";
        if(stats.located()==0)return new MapResult(stats.total(),0,0,null,List.of(),level);
        if(!"STORE".equals(level))return administrativeMap(scope,f,stats,window,level);
        var bounds=window.west()==null?stats.bounds():new MapBounds(window.west(),window.south(),window.east(),window.north());
        // 约 80px 一个聚合格；按视野跨度设置下限，保证全国查询也只有有限网格，不截断门店。
        double grid=Math.max(360/Math.pow(2,window.zoom()==null?4:window.zoom())/4,
                Math.max((bounds.east()-bounds.west())/24,(bounds.north()-bounds.south())/16));
        var visible=mapFilter(f,window,null);
        double gpsGrid=window.west()==null?grid:Math.max(grid,Math.max((bounds.east()-bounds.west()+.04)/24,(bounds.north()-bounds.south()+.04)/16));
        long visibleCount=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+FROM+" WHERE "+visible.sql(),Long.class,visible.args().toArray()));
        // 街道层优先展示真实位置；密集视野才保留聚合，不能按条数截断门店。
        boolean exact=visibleCount<=800;
        var args=new ArrayList<Object>();
        if(!exact){args.add(gpsGrid);args.add(grid);args.add(gpsGrid);args.add(grid);}
        args.addAll(visible.args());
        String cells=exact?"s.longitude AS gx,s.latitude AS gy,":
                "FLOOR(s.longitude/CASE WHEN s.coordinate_system='WGS84' THEN ? ELSE ? END) AS gx,"
                +"FLOOR(s.latitude/CASE WHEN s.coordinate_system='WGS84' THEN ? ELSE ? END) AS gy,";
        String sql="SELECT "+cells+"s.coordinate_system,COUNT(*) AS amount,"
                +"MIN(s.id) AS store_id,MIN(s.name) AS name,AVG(s.longitude) AS lng,AVG(s.latitude) AS lat,"
                +"SUM(q.pool_type='PRIVATE') AS private_count,SUM(q.cooperation_status='COOPERATED') AS cooperated_count,"
                +"MIN(s.longitude) AS west,MAX(s.longitude) AS east,MIN(s.latitude) AS south,MAX(s.latitude) AS north"
                +FROM+" WHERE "+visible.sql()+" GROUP BY gx,gy,s.coordinate_system ORDER BY gx,gy,s.coordinate_system";
        var points=jdbc.query(sql,(rs,n)->{
            long count=rs.getLong("amount");String system=rs.getString("coordinate_system");
            return new MapPoint(system+":"+rs.getString("gx")+":"+rs.getString("gy"),count==1?rs.getString("store_id"):null,
                    count==1?rs.getString("name"):null,rs.getDouble("lng"),rs.getDouble("lat"),system,count,
                    rs.getLong("private_count"),rs.getLong("cooperated_count"),
                    new MapBounds(rs.getDouble("west"),rs.getDouble("south"),rs.getDouble("east"),rs.getDouble("north")),null,null);
        },args.toArray());
        return new MapResult(stats.total(),stats.located(),points.stream().mapToLong(MapPoint::count).sum(),stats.bounds(),points,level);
    }
    private MapResult administrativeMap(StoreScope scope,Filter filter,MapStats stats,MapWindow window,String level) {
        // 先按完整授权/筛选范围汇总，再裁剪区域；不能用视野先裁剪门店而改变同一区域的数字。
        String area=switch(level) {
            case "PROVINCE" -> "q.province_code";
            case "PREFECTURE" -> "COALESCE(q.city_code,q.county_code,q.admin_area_code)";
            default -> "COALESCE(q.county_code,q.city_code,q.admin_area_code)";
        };
        String sql="SELECT "+area+" AS area_code,COUNT(*) AS amount,"
                +"SUM(q.pool_type='PRIVATE') AS private_count,SUM(q.cooperation_status='COOPERATED') AS cooperated_count,"
                +"AVG(CASE WHEN "+LOCATED+" AND s.coordinate_system='GCJ02' THEN s.longitude END) AS gcj_lng,"
                +"AVG(CASE WHEN "+LOCATED+" AND s.coordinate_system='GCJ02' THEN s.latitude END) AS gcj_lat,"
                +"AVG(CASE WHEN "+LOCATED+" AND s.coordinate_system='WGS84' THEN s.longitude END) AS gps_lng,"
                +"AVG(CASE WHEN "+LOCATED+" AND s.coordinate_system='WGS84' THEN s.latitude END) AS gps_lat,"
                +"MIN(CASE WHEN "+LOCATED+" THEN s.longitude END) AS west,MAX(CASE WHEN "+LOCATED+" THEN s.longitude END) AS east,"
                +"MIN(CASE WHEN "+LOCATED+" THEN s.latitude END) AS south,MAX(CASE WHEN "+LOCATED+" THEN s.latitude END) AS north"
                +FROM+" WHERE "+filter.sql()+" GROUP BY area_code HAVING west IS NOT NULL ORDER BY area_code";
        var points=jdbc.query(sql,(rs,n)->{
            String code=rs.getString("area_code");var node=scope.nodes().get(code);
            boolean gcj=rs.getObject("gcj_lng")!=null;
            // 标记采用本区门店分布中心；不同坐标系不混算，也不冒充行政驻地。
            return new MapPoint("AREA:"+code,null,node==null?code:node.name(),
                    rs.getDouble(gcj?"gcj_lng":"gps_lng"),rs.getDouble(gcj?"gcj_lat":"gps_lat"),gcj?"GCJ02":"WGS84",
                    rs.getLong("amount"),rs.getLong("private_count"),rs.getLong("cooperated_count"),
                    new MapBounds(rs.getDouble("west"),rs.getDouble("south"),rs.getDouble("east"),rs.getDouble("north")),
                    code,node==null?level:node.level());
        },filter.args().toArray()).stream().filter(point->{
            if(window.west()==null)return true;
            var b=point.bounds();
            return b.east()+.02>=window.west()&&b.west()-.02<=window.east()&&b.north()+.02>=window.south()&&b.south()-.02<=window.north();
        }).toList();
        return new MapResult(stats.total(),stats.located(),points.stream().mapToLong(MapPoint::count).sum(),stats.bounds(),points,level);
    }
    private static Filter mapFilter(Filter base,MapWindow window,String coordinateSystem) {
        var args=new ArrayList<>(base.args());var sql=new StringBuilder(base.sql()).append(" AND ").append(LOCATED);
        if(coordinateSystem!=null)condition(sql,args,"s.coordinate_system",coordinateSystem);
        if(window.west()!=null) {
            // 浏览器视野为 GCJ02；GPS 数据在边界额外取 0.02 度，转换后由地图裁剪，避免遗漏边缘门店。
            // 点击聚合查看成员时传入原始坐标系及精确边界，不扩展范围。
            String margin=coordinateSystem==null?"CASE WHEN s.coordinate_system='WGS84' THEN 0.02 ELSE 0 END":"0";
            sql.append(" AND s.longitude BETWEEN ?-"+margin+" AND ?+"+margin+" AND s.latitude BETWEEN ?-"+margin+" AND ?+"+margin);
            args.add(window.west());args.add(window.east());args.add(window.south());args.add(window.north());
        }
        return new Filter(sql.toString(),args);
    }
    @Override
    @Transactional(readOnly=true)
    public Page mapStores(StoreScope scope,Query query,Set<String> typeCodes,MapWindow window,String coordinateSystem) {
        var totalFilter=mapFilter(filter(scope,query,false,typeCodes),window,coordinateSystem);
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+FROM+" WHERE "+totalFilter.sql(),Long.class,totalFilter.args().toArray()));
        var f=mapFilter(filter(scope,query,true,typeCodes),window,coordinateSystem);
        var args=new ArrayList<Object>();args.add(scope.access().employeeId());args.addAll(f.args());args.add(query.size()+1);
        var rows=jdbc.query(select(scope)+" WHERE "+f.sql()+" ORDER BY q.store_id DESC LIMIT ?",(rs,n)->row(scope,rs),args.toArray());
        boolean more=rows.size()>query.size();var items=more?rows.subList(0,query.size()):rows;
        return new Page(total,List.copyOf(items),more?items.getLast().id():null);
    }
    @Override
    public Row detail(StoreScope scope,String id) {
        String view=switch(scope.access().action()){case "crm:pool:public-read"->"PUBLIC";case "crm:pool:private-read"->"MINE";case "crm:pool:team-read"->"TEAM";case "crm:store:cooperation-read"->"COOPERATED";default->"ALL";};
        var q=new Query(view,null,null,null,null,null,null,null,null,null,null,20);
        var f=filter(scope,q,false);var args=new ArrayList<Object>();args.add(scope.access().employeeId());args.addAll(f.args());args.add(id);
        var rows=jdbc.query(select(scope)+" WHERE "+f.sql()+" AND q.store_id=?",(rs,n)->row(scope,rs),args.toArray());
        if(rows.isEmpty())throw new AuthorizationDeniedException("store-not-visible");return rows.getFirst();
    }
    private Row row(StoreScope scope,ResultSet rs)throws SQLException {
        var nodes=scope.nodes();String province=rs.getString("province_code"),city=rs.getString("city_code"),county=rs.getString("county_code");
        var cooldown=instant(rs,"cooldown_until");String blocked=null;
        if(!"PUBLIC".equals(rs.getString("pool_type")))blocked="门店已被认领";
        else if(scope.access().employeeId()==null)blocked="账号尚未关联有效员工";
        else if(cooldown!=null&&cooldown.isAfter(Instant.now()))blocked="本人冷却中";
        else if(rs.getBigDecimal("longitude")==null||rs.getBigDecimal("latitude")==null||"PENDING_COUNTY".equals(rs.getString("area_resolution")))blocked="门店资料待完善";
        String ownerId=rs.getString("owner_employee_id");
        String ownerName=Objects.equals(ownerId,scope.access().employeeId())?scope.access().employeeName():rs.getString("owner_employee_code");
        return new Row(rs.getString("id"),rs.getString("name"),rs.getString("address"),rs.getString("public_phone"),rs.getString("store_type_code"),rs.getString("primary_poi"),instant(rs,"created_at"),rs.getString("created_by"),instant(rs,"updated_at"),rs.getString("updated_by"),rs.getString("admin_area_code"),
                nodes.containsKey(province)?nodes.get(province).name():null,nodes.containsKey(city)?nodes.get(city).name():null,nodes.containsKey(county)?nodes.get(county).name():null,
                rs.getString("geo_region_code"),rs.getBigDecimal("longitude"),rs.getBigDecimal("latitude"),rs.getString("coordinate_system"),rs.getString("record_status"),rs.getString("pool_type"),ownerId,ownerName,
                rs.getString("cooperation_status"),rs.getString("customer_tier"),rs.getString("tier_unrated_reason"),rs.getString("effective_activity"),rs.getString("potential_level"),
                instant(rs,"last_visit_at"),instant(rs,"last_order_at"),rs.getString("phase"),instant(rs,"deadline_at"),cooldown,blocked,rs.getLong("summary_revision"),rs.getString("current_ownership_id"),rs.getString("hold_reason"),instant(rs,"hold_until"),rs.getObject("valid_visit_count")==null?null:rs.getLong("valid_visit_count"));
    }
    private static Instant instant(ResultSet rs,String name)throws SQLException {Timestamp t=rs.getTimestamp(name);return t==null?null:t.toInstant();}
    private void lock(StoreScope scope,String id) {
        var rows=jdbc.queryForList("SELECT id FROM crm_store_master WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,scope.access().tenantId(),id);
        if(rows.isEmpty())throw new AuthorizationDeniedException("store-not-visible");
    }
    private Map<String,Object> policy(StoreScope scope) {
        var rows=jdbc.queryForList("SELECT * FROM crm_pool_policy WHERE tenant_id=? AND status_code='PUBLISHED' AND effective_from<=UTC_TIMESTAMP(6) ORDER BY effective_from DESC,id DESC LIMIT 1",scope.access().tenantId());
        if(rows.isEmpty())throw new StateConflictException("公海规则尚未启用，请由管理员完成启用配置");return rows.getFirst();
    }
    @Override
    @Transactional
    public void claim(StoreScope scope,String id,Claim command) {
        lock(scope,id);var current=detail(scope,id);
        var previous=jdbc.queryForList("SELECT store_id,owner_employee_id FROM crm_store_ownership WHERE tenant_id=? AND request_id=?",scope.access().tenantId(),command.requestId());
        if(!previous.isEmpty()) {
            var p=previous.getFirst();if(id.equals(p.get("store_id").toString())&&scope.access().employeeId().equals(p.get("owner_employee_id").toString()))return;
            throw new StateConflictException("请求标识已被其他认领使用");
        }
        if(current.revision()!=command.revision())throw new StateConflictException("门店状态已变化，请刷新后重试");
        if(current.claimBlockedReason()!=null)throw new StateConflictException(current.claimBlockedReason());
        var policy=policy(scope);long ownership=IdWorker.getId();Instant now=Instant.now();
        Instant deadline=now.plusSeconds(((Number)policy.get("first_visit_hours")).longValue()*3600);
        jdbc.update("INSERT INTO crm_store_ownership(id,tenant_id,store_id,owner_employee_id,department_id_at_start,acquire_type,started_at,policy_id,phase,deadline_at,request_id,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,'CLAIM',?,?,'FIRST_VISIT',?,?,?,?,?,?)",
                ownership,scope.access().tenantId(),id,scope.access().employeeId(),scope.access().departmentId(),Timestamp.from(now),policy.get("id"),Timestamp.from(deadline),command.requestId(),Timestamp.from(now),Timestamp.from(now),scope.access().userId(),scope.access().userId());
        jdbc.update("UPDATE crm_store_summary SET current_ownership_id=?,owner_employee_id=?,owner_employee_code=?,ownership_revision=1,phase='FIRST_VISIT',deadline_at=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",
                ownership,scope.access().employeeId(),scope.access().employeeCode(),Timestamp.from(deadline),Timestamp.from(now),scope.access().tenantId(),id);
        event(scope,id,ownership,"OWNERSHIP",ownership,"CLAIMED",command.requestId(),null);
    }
    @Override
    @Transactional
    public void release(StoreScope scope,String id,Release command) {
        lock(scope,id);var current=detail(scope,id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_event WHERE tenant_id=? AND request_id=? AND event_type='RELEASED' AND store_id=? AND actor_user_id=?",Integer.class,scope.access().tenantId(),command.requestId(),id,scope.access().userId())>0)return;
        if(current.revision()!=command.revision())throw new StateConflictException("门店状态已变化，请刷新后重试");
        if(!Objects.equals(current.ownerEmployeeId(),scope.access().employeeId()))throw new AuthorizationDeniedException("store-current-owner");
        var rows=jdbc.queryForList("SELECT o.id,p.return_cooldown_hours FROM crm_store_ownership o JOIN crm_pool_policy p ON p.tenant_id=o.tenant_id AND p.id=o.policy_id WHERE o.tenant_id=? AND o.store_id=? AND o.ended_at IS NULL FOR UPDATE",scope.access().tenantId(),id);
        if(rows.size()!=1)throw new StateConflictException("当前归属已变化");
        var o=rows.getFirst();long ownership=((Number)o.get("id")).longValue();Instant now=Instant.now();
        jdbc.update("UPDATE crm_store_ownership SET ended_at=?,end_reason='VOLUNTARY_RETURN',cooldown_until=?,updated_at=?,updated_by=?,revision=revision+1 WHERE tenant_id=? AND id=?",
                Timestamp.from(now),Timestamp.from(now.plusSeconds(((Number)o.get("return_cooldown_hours")).longValue()*3600)),Timestamp.from(now),scope.access().userId(),scope.access().tenantId(),ownership);
        jdbc.update("UPDATE crm_store_summary SET current_ownership_id=NULL,owner_employee_id=NULL,owner_employee_code=NULL,ownership_revision=NULL,phase=NULL,deadline_at=NULL,revision=revision+1,updated_at=? WHERE tenant_id=? AND store_id=?",Timestamp.from(now),scope.access().tenantId(),id);
        event(scope,id,ownership,"OWNERSHIP",ownership,"RELEASED",command.requestId(),command.reason());
    }
    @Override
    public List<Ownership> ownerships(StoreScope scope,String id) {
        detail(scope,id);
        return jdbc.query("SELECT id,owner_employee_id,acquire_type,phase,started_at,ended_at,end_reason,deadline_at FROM crm_store_ownership WHERE tenant_id=? AND store_id=? ORDER BY started_at DESC,id DESC LIMIT 100",
                (rs,n)->new Ownership(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),instant(rs,"started_at"),instant(rs,"ended_at"),rs.getString(7),instant(rs,"deadline_at")),scope.access().tenantId(),id);
    }
    @Override
    public List<Lead> leads(StoreScope scope,boolean review) {
        var codes=scope.catalog().nodes().stream().filter(n->scope.permitsArea(n.code())).map(n->n.code()).toList();
        if(codes.isEmpty())return List.of();
        var args=new ArrayList<Object>();args.add(scope.access().tenantId());args.add(scope.catalog().version());args.addAll(codes);
        String owner=review?" AND status_code='PENDING_REVIEW'":" AND submitter_employee_id=?";
        if(!review)args.add(scope.access().employeeId());
        return jdbc.query("SELECT * FROM crm_store_lead WHERE tenant_id=? AND proposed_admin_area_version=? AND proposed_admin_area_code IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")"+owner+" ORDER BY submitted_at DESC,id DESC LIMIT 100",(rs,n)->lead(rs),args.toArray());
    }
    private Lead lead(ResultSet rs)throws SQLException {
        return new Lead(rs.getString("id"),rs.getString("proposed_name"),rs.getString("proposed_address"),rs.getString("proposed_admin_area_code"),rs.getBigDecimal("proposed_longitude"),rs.getBigDecimal("proposed_latitude"),rs.getString("status_code"),instant(rs,"submitted_at"),instant(rs,"reviewed_at"),rs.getString("review_reason"),rs.getString("result_store_id"),rs.getLong("revision"));
    }
    @Override
    @Transactional
    public Lead submit(StoreScope scope,LeadCommand c) {
        var prior=jdbc.query("SELECT * FROM crm_store_lead WHERE tenant_id=? AND request_id=?",(rs,n)->lead(rs),scope.access().tenantId(),c.requestId());
        if(!prior.isEmpty()) {
            int owned=jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_lead WHERE tenant_id=? AND request_id=? AND submitter_employee_id=? AND proposed_name=? AND proposed_address=? AND proposed_admin_area_code=? AND proposed_longitude=? AND proposed_latitude=?",Integer.class,scope.access().tenantId(),c.requestId(),scope.access().employeeId(),c.name().strip(),c.address().strip(),c.areaCode(),c.longitude(),c.latitude());
            if(owned!=1)throw new StateConflictException("请求标识已用于其他提报");return prior.getFirst();
        }
        duplicate(scope,c.name().strip(),c.address().strip(),c.areaCode());
        long id=IdWorker.getId();Instant now=Instant.now();
        jdbc.update("INSERT INTO crm_store_lead(id,tenant_id,request_type,proposed_name,proposed_address,proposed_admin_area_code,proposed_admin_area_version,proposed_longitude,proposed_latitude,proposed_coordinate_system,proposed_public_phone,submitter_employee_id,status_code,submitted_at,request_id,created_at,updated_at,created_by,updated_by) VALUES(?,?,'CREATE_STORE',?,?,?,?,?,?,'GCJ02',?,?,'PENDING_REVIEW',?,?,?,?,?,?)",
                id,scope.access().tenantId(),c.name().strip(),c.address().strip(),c.areaCode(),scope.catalog().version(),c.longitude(),c.latitude(),c.publicPhone(),scope.access().employeeId(),Timestamp.from(now),c.requestId(),Timestamp.from(now),Timestamp.from(now),scope.access().userId(),scope.access().userId());
        event(scope,null,null,"LEAD",id,"SUBMITTED",c.requestId(),null);
        return jdbc.query("SELECT * FROM crm_store_lead WHERE tenant_id=? AND id=?",(rs,n)->lead(rs),scope.access().tenantId(),id).getFirst();
    }
    private void duplicate(StoreScope scope,String name,String address,String area) {
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_master WHERE tenant_id=? AND admin_area_version=? AND admin_area_code=? AND name=? AND address=? AND record_status<>'MERGED'",Integer.class,scope.access().tenantId(),scope.catalog().version(),area,name,address);
        // Do not leak another employee's private store identity through deduplication.
        if(count>0)throw new StateConflictException("该门店疑似已存在，请查询已有门店或联系管理员核对");
    }
    @Override
    @Transactional
    public Lead review(StoreScope scope,String id,Review command) {
        var rows=jdbc.queryForList("SELECT * FROM crm_store_lead WHERE tenant_id=? AND id=? FOR UPDATE",scope.access().tenantId(),id);
        if(rows.isEmpty())throw new AuthorizationDeniedException("lead-not-visible");var data=rows.getFirst();String area=(String)data.get("proposed_admin_area_code");
        if(!Objects.equals(scope.catalog().version(),data.get("proposed_admin_area_version"))||!scope.permitsArea(area))throw new AuthorizationDeniedException("lead-area");
        if(!"PENDING_REVIEW".equals(data.get("status_code"))||((Number)data.get("revision")).longValue()!=command.revision())throw new StateConflictException("提报已被处理，请刷新");
        Long storeId=null;
        if(command.approve()) {
            var policy=policy(scope);
            // Serialize admissions within the published tenant policy while checking duplicates.
            jdbc.queryForList("SELECT id FROM crm_pool_policy WHERE tenant_id=? AND id=? FOR UPDATE",scope.access().tenantId(),policy.get("id"));
            duplicate(scope,(String)data.get("proposed_name"),(String)data.get("proposed_address"),area);
            storeId=createStore(scope,data);
        }
        jdbc.update("UPDATE crm_store_lead SET status_code=?,reviewed_at=UTC_TIMESTAMP(6),reviewer_user_id=?,review_reason=?,result_store_id=?,revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=? WHERE tenant_id=? AND id=?",
                command.approve()?"APPROVED":"REJECTED",scope.access().userId(),command.reason(),storeId,scope.access().userId(),scope.access().tenantId(),id);
        event(scope,storeId==null?null:storeId.toString(),null,"LEAD",Long.parseLong(id),command.approve()?"APPROVED":"REJECTED",command.requestId(),command.reason());
        return jdbc.query("SELECT * FROM crm_store_lead WHERE tenant_id=? AND id=?",(rs,n)->lead(rs),scope.access().tenantId(),id).getFirst();
    }
    private long createStore(StoreScope scope,Map<String,Object> data) {
        long id=IdWorker.getId();String area=(String)data.get("proposed_admin_area_code");var chain=scope.ancestry(area);
        var province=chain.stream().filter(n->"PROVINCE".equals(n.level())).findFirst().orElseThrow();
        var city=chain.stream().filter(n->"PREFECTURE".equals(n.level())).findFirst().orElse(null);
        boolean county="COUNTY".equals(chain.getFirst().level());
        String cityCode=city!=null?city.code():Set.of("110000","120000","310000","500000").contains(province.code())?province.code():null;
        String resolution=county?"COUNTY":"LEGAL_CITY_LEAF";
        jdbc.update("INSERT INTO crm_store_master(id,tenant_id,name,address,public_phone,admin_area_code,admin_area_version,area_resolution,longitude,latitude,coordinate_system,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,'GCJ02',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)",
                id,scope.access().tenantId(),data.get("proposed_name"),data.get("proposed_address"),data.get("proposed_public_phone"),area,scope.catalog().version(),resolution,data.get("proposed_longitude"),data.get("proposed_latitude"),scope.access().userId(),scope.access().userId());
        jdbc.update("INSERT INTO crm_store_summary(tenant_id,store_id,updated_at,record_status,admin_area_code,admin_area_version,area_resolution,geo_region_code,province_code,city_code,county_code,tier_unrated_reason) VALUES(?,?,UTC_TIMESTAMP(6),'ACTIVE',?,?,?,?,?,?,?,'DATA_INCOMPLETE')",
                scope.access().tenantId(),id,area,scope.catalog().version(),resolution,province.regionCode(),province.code(),cityCode,county?area:null);
        return id;
    }
    private void event(StoreScope scope,String storeId,Long ownership,String type,long object,String event,String request,String reason) {
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,ownership_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,request_id,event_seq,reason) VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,1,?)",
                IdWorker.getId(),scope.access().tenantId(),storeId,ownership,type,object,event,scope.access().userId(),scope.access().employeeId(),request,reason);
    }
    @Override
    @Transactional
    public void initializePolicy(StoreScope scope) {
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO crm_pool_policy(id,tenant_id,policy_version,first_visit_hours,followup_days,cooperating_days,return_cooldown_hours,status_code,effective_from,published_at,published_by,description,created_at,updated_at,created_by,updated_by) VALUES(?,?,1,24,7,60,48,'PUBLISHED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,'首次启用已确认的24小时/7天/60天及48小时冷却规则',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?) ON DUPLICATE KEY UPDATE id=id",
                id,scope.access().tenantId(),scope.access().userId(),scope.access().userId(),scope.access().userId());
    }
    @Override
    public List<Event> events(StoreScope scope,String id) {
        detail(scope,id);
        return jdbc.query("SELECT id,event_type,actor_user_id,occurred_at,before_data,after_data,reason FROM crm_store_event WHERE tenant_id=? AND store_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100",
                (rs,n)->new Event(rs.getString(1),rs.getString(2),rs.getString(3),instant(rs,"occurred_at"),rs.getString(5),rs.getString(6),rs.getString(7)),scope.access().tenantId(),id);
    }
    @Override
    @Transactional
    public void update(StoreScope scope,String id,Update c) {
        lock(scope,id);var old=detail(scope,id);
        if(old.revision()!=c.revision())throw new StateConflictException("门店资料已变化，请刷新");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_event WHERE tenant_id=? AND request_id=?",Integer.class,scope.access().tenantId(),c.requestId())>0)throw new StateConflictException("修改请求已处理，请刷新");
        jdbc.update("UPDATE crm_store_master SET name=?,address=?,public_phone=?,store_type_code=?,revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=? WHERE tenant_id=? AND id=?",
                c.name().strip(),c.address().strip(),c.publicPhone(),c.storeTypeCode(),scope.access().userId(),scope.access().tenantId(),id);
        jdbc.update("UPDATE crm_store_summary SET revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND store_id=?",scope.access().tenantId(),id);
        jdbc.update("INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,actor_employee_id,occurred_at,before_data,after_data,reason,request_id,event_seq) VALUES(?,?,?,'STORE',?,'PROFILE_UPDATED',?,?,UTC_TIMESTAMP(6),JSON_OBJECT('name',?,'address',?,'publicPhone',?,'storeTypeCode',?),JSON_OBJECT('name',?,'address',?,'publicPhone',?,'storeTypeCode',?),?,?,1)",
                IdWorker.getId(),scope.access().tenantId(),id,id,scope.access().userId(),scope.access().employeeId(),old.name(),old.address(),old.publicPhone(),old.storeTypeCode(),c.name().strip(),c.address().strip(),c.publicPhone(),c.storeTypeCode(),c.reason(),c.requestId());
    }
    @Override
    public Coverage coverage(StoreScope scope,Query query,Set<String> typeCodes) {
        var f=filter(scope,query,false,typeCodes);
        return jdbc.queryForObject("SELECT COUNT(*) AS total,COALESCE(SUM(q.pool_type='PUBLIC'),0) AS public_count,COALESCE(SUM(q.pool_type='PRIVATE'),0) AS private_count,COALESCE(SUM(q.cooperation_status='COOPERATED'),0) AS cooperated,COALESCE(SUM(q.valid_visit_count>0),0) AS visited,COALESCE(SUM(q.wecom_active_contact_count>0),0) AS connected,COALESCE(SUM(q.customer_tier='A'),0) AS a,COALESCE(SUM(q.customer_tier='B'),0) AS b,COALESCE(SUM(q.customer_tier='C'),0) AS c,COALESCE(SUM(q.customer_tier='UNRATED'),0) AS unrated,COALESCE(SUM(q.visit_data_status<>'COMPLETE'),0) AS unknown_visit,COALESCE(SUM(q.wecom_data_status<>'COMPLETE'),0) AS unknown_wecom"+FROM+" WHERE "+f.sql(),
                (rs,n)->new Coverage(rs.getLong("total"),rs.getLong("public_count"),rs.getLong("private_count"),rs.getLong("cooperated"),rs.getLong("visited"),rs.getLong("connected"),rs.getLong("a"),rs.getLong("b"),rs.getLong("c"),rs.getLong("unrated"),rs.getLong("unknown_visit"),rs.getLong("unknown_wecom")),f.args().toArray());
    }
}
