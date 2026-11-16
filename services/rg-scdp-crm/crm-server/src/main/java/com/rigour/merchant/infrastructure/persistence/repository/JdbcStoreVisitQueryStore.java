package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.*;
import com.rigour.merchant.application.port.out.StoreVisitQueryStore;
import com.rigour.merchant.application.service.StoreScope;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly=true)
public class JdbcStoreVisitQueryStore implements StoreVisitQueryStore {
    private final JdbcTemplate jdbc;
    public JdbcStoreVisitQueryStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String OWN=" FROM crm_visit v WHERE v.tenant_id=? AND v.salesperson_id=? ";
    private static final String PERIOD=" AND v.visited_at>=? AND v.visited_at<? ";
    private static final String FIRST=" NOT EXISTS (SELECT 1 FROM crm_visit p WHERE p.tenant_id=v.tenant_id AND p.salesperson_id=v.salesperson_id AND p.store_id=v.store_id AND (p.visited_at<v.visited_at OR (p.visited_at=v.visited_at AND p.id<v.id))) ";
    private static final String STATE=" CASE WHEN v.checked_out_at IS NOT NULL AND v.submitted_at IS NULL AND v.status_code='IN_PROGRESS' THEN 'PENDING_SUBMIT' ELSE v.status_code END ";
    private static final String FIELDS="v.id,v.store_id,v.capture_ref,v.store_snapshot,"+STATE+" display_status,v.visited_at,v.checked_out_at,v.review_reason";
    private static String text(tools.jackson.databind.JsonNode node,String key){var value=node.get(key);return value==null||value.isNull()?"":value.asText();}
    private static Row row(ResultSet r,StoreScope scope)throws SQLException {
        var snapshot=new tools.jackson.databind.ObjectMapper().readTree(r.getString("store_snapshot"));
        String address=text(snapshot,"address");
        var node=scope.nodes().get(text(snapshot,"areaCode"));
        if(node!=null) {
            var names=new ArrayList<String>();var current=node;
            while(current!=null){if(!names.contains(current.name()))names.add(0,current.name());current=scope.nodes().get(current.parentCode());}
            String prefix=String.join("",names);
            if(!prefix.isBlank()&&!address.startsWith(prefix))address=prefix+" · "+address;
        }
        var out=r.getTimestamp("checked_out_at");
        return new Row(r.getString("id"),r.getString("store_id"),r.getString("capture_ref"),text(snapshot,"name"),address,
            r.getString("display_status"),r.getTimestamp("visited_at").toInstant(),out==null?null:out.toInstant(),r.getString("review_reason"));
    }
    @Override public Page list(StoreScope scope,Instant from,Instant to,String kind,String status,String keyword,int page,int size) {
        // A personal visit grants only its immutable snapshot, never another owner's current customer data.
        var args=new ArrayList<Object>(List.of(scope.access().tenantId(),scope.access().employeeId(),Timestamp.from(from),Timestamp.from(to)));
        String period=OWN+PERIOD;
        var summary=jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(v.status_code='EFFECTIVE'),0) effective,COALESCE(SUM("+FIRST+"),0) first_count"+period,
            (r,n)->new Summary(r.getLong("total"),r.getLong("effective"),r.getLong("first_count")),args.toArray());
        String filter=period;
        if("effective".equals(kind))filter+=" AND v.status_code='EFFECTIVE'";
        if("first".equals(kind))filter+=" AND "+FIRST;
        if(!"ALL".equals(status)){filter+=" AND ("+STATE+")=?";args.add(status);}
        if(keyword!=null&&!keyword.isBlank()) {
            filter+=" AND (LOCATE(?,JSON_UNQUOTE(JSON_EXTRACT(v.store_snapshot,'$.name')))>0 OR LOCATE(?,JSON_UNQUOTE(JSON_EXTRACT(v.store_snapshot,'$.address')))>0)";
            args.add(keyword.strip());args.add(keyword.strip());
        }
        long total=jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact(page-1,size));
        var rows=jdbc.query("SELECT "+FIELDS+filter+" ORDER BY v.visited_at DESC,v.id DESC LIMIT ? OFFSET ?",(r,n)->row(r,scope),args.toArray());
        var active=jdbc.query("SELECT "+FIELDS+OWN+" AND v.status_code='IN_PROGRESS' AND v.checked_out_at IS NULL ORDER BY v.visited_at DESC,v.id DESC LIMIT 1",
            (r,n)->row(r,scope),scope.access().tenantId(),scope.access().employeeId());
        return new Page(rows,total,summary,active.isEmpty()?null:active.getFirst());
    }
}
