package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.CustomerWorkbenchModels.*;
import com.rigour.merchant.application.port.out.CustomerWorkbenchStore;
import com.rigour.merchant.application.service.StoreScope;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly=true)
public class JdbcCustomerWorkbenchStore implements CustomerWorkbenchStore {
    private final JdbcTemplate jdbc;
    public JdbcCustomerWorkbenchStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String BASE=" FROM crm_store_summary q JOIN crm_store_master s ON s.tenant_id=q.tenant_id AND s.id=q.store_id ";
    private static final String CONTACT_MISSING=" NOT EXISTS (SELECT 1 FROM crm_store_contact c JOIN crm_contact_method m ON m.tenant_id=c.tenant_id AND m.contact_id=c.contact_id WHERE c.tenant_id=q.tenant_id AND c.store_id=q.store_id AND c.status_code='ACTIVE' AND m.method_type='PHONE' AND m.status_code='VERIFIED') AND NOT EXISTS (SELECT 1 FROM crm_store_contact wc JOIN crm_wecom_identity wi ON wi.tenant_id=wc.tenant_id AND wi.contact_id=wc.contact_id JOIN crm_wecom_relation wr ON wr.tenant_id=wi.tenant_id AND wr.identity_id=wi.id WHERE wc.tenant_id=q.tenant_id AND wc.store_id=q.store_id AND wc.status_code='ACTIVE' AND wr.active=1) ";
    private record Filter(String sql,List<Object> args) {}
    private static Filter mine(StoreScope scope) {
        var access=StoreScopeSql.compile(scope);var args=new ArrayList<Object>();
        args.add(scope.access().tenantId());args.add(scope.catalog().version());args.add(scope.access().employeeId());args.addAll(access.args());
        return new Filter("q.tenant_id=? AND q.admin_area_version=? AND q.owner_employee_id=? AND q.pool_type='PRIVATE' AND q.record_status='ACTIVE' AND ("+access.sql()+")",args);
    }
    private Filter filter(StoreScope scope,String kind,Instant from,Instant to,String keyword,String relation) {
        var args=new ArrayList<Object>();StringBuilder sql;
        if("CONTACT_PENDING".equals(kind)) {var own=mine(scope);args.addAll(own.args());sql=new StringBuilder(own.sql()).append(" AND ").append(CONTACT_MISSING);}
        else {
            args.add(scope.access().tenantId());args.add(scope.access().employeeId());
            // An immutable personal order fact grants only this minimal historical projection,
            // never permission to read another owner's contacts or maintain their store.
            sql=new StringBuilder("q.tenant_id=? AND EXISTS (SELECT 1 FROM crm_store_order_fact f WHERE f.tenant_id=q.tenant_id AND f.store_id=q.store_id AND f.salesperson_id=? AND f.valid_order=1");
            if(!"COOPERATED".equals(kind)) {sql.append(" AND f.effective_at>=? AND f.effective_at<?");args.add(Timestamp.from(from));args.add(Timestamp.from(to));}
            if("NEW".equals(kind)) {
                sql.append(" AND q.first_cooperated_at=f.effective_at AND NOT EXISTS (SELECT 1 FROM crm_store_order_fact earlier WHERE earlier.tenant_id=f.tenant_id AND earlier.store_id=f.store_id AND earlier.valid_order=1 AND (earlier.effective_at<f.effective_at OR (earlier.effective_at=f.effective_at AND earlier.order_id<f.order_id)))");
            }
            sql.append(')');
            if("RETURNING".equals(kind)) {sql.append(" AND (q.first_cooperated_at<? OR EXISTS (SELECT 1 FROM crm_store_order_fact h WHERE h.tenant_id=q.tenant_id AND h.store_id=q.store_id AND h.valid_order=1 AND h.effective_at<?))");args.add(Timestamp.from(from));args.add(Timestamp.from(from));}
            if("UNKNOWN_HISTORY".equals(kind)) sql.append(" AND q.first_cooperated_at IS NULL");
        }
        if(keyword!=null&&!keyword.isBlank()){sql.append(" AND (LOCATE(?,s.name)>0 OR LOCATE(?,s.address)>0)");args.add(keyword.strip());args.add(keyword.strip());}
        if("MINE".equals(relation)){sql.append(" AND q.pool_type='PRIVATE' AND q.owner_employee_id=?");args.add(scope.access().employeeId());}
        if("PUBLIC".equals(relation))sql.append(" AND q.pool_type='PUBLIC'");
        if("TRANSFERRED".equals(relation)){sql.append(" AND q.pool_type='PRIVATE' AND q.owner_employee_id<>?");args.add(scope.access().employeeId());}
        return new Filter(sql.toString(),args);
    }
    @Override public Page customers(StoreScope scope,String kind,Instant from,Instant to,String keyword,String relation,String cursor,int size) {
        var f=filter(scope,kind,from,to,keyword,relation);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+BASE+" WHERE "+f.sql(),Long.class,f.args().toArray());
        var args=new ArrayList<Object>();args.add(scope.access().employeeId());args.add(scope.access().employeeId());args.addAll(f.args());
        String paging="";if(cursor!=null&&!cursor.isBlank()){paging=" AND q.store_id<?";args.add(cursor);}args.add(size+1);
        var rows=jdbc.query("SELECT s.id,s.name,s.address,q.pool_type,q.owner_employee_id,(SELECT MAX(o.effective_at) FROM crm_store_order_fact o WHERE o.tenant_id=q.tenant_id AND o.store_id=q.store_id AND o.salesperson_id=? AND o.valid_order=1) last_order,(SELECT COUNT(*) FROM crm_store_order_fact o WHERE o.tenant_id=q.tenant_id AND o.store_id=q.store_id AND o.salesperson_id=? AND o.valid_order=1) order_count"+BASE+" WHERE "+f.sql()+paging+" ORDER BY q.store_id DESC LIMIT ?",(r,n)->new Item(r.getString("id"),r.getString("name"),r.getString("address"),"PUBLIC".equals(r.getString("pool_type"))?"PUBLIC":Objects.equals(scope.access().employeeId(),r.getString("owner_employee_id"))?"MINE":"TRANSFERRED",instant(r,"last_order"),r.getLong("order_count")),args.toArray());
        boolean more=rows.size()>size;var items=more?rows.subList(0,size):rows;
        return new Page(total,List.copyOf(items),more?items.getLast().id():null);
    }
    @Override public NearbyModels.Page<Item> nearby(StoreScope scope,String kind,Instant from,Instant to,String keyword,String relation,String cursor,int size,NearbyModels.Origin origin) {
        var f=filter(scope,kind,from,to,keyword,relation);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+BASE+" WHERE "+f.sql(),Long.class,f.args().toArray());
        if(total==0)return new NearbyModels.Page<>(0,List.of(),null,origin);
        var nearby=NearbyStoreQuery.search(jdbc,BASE,f.sql(),f.args(),origin,cursor,size,total);
        if(nearby.hits().isEmpty())return new NearbyModels.Page<>(total,List.of(),null,origin);
        var args=new ArrayList<Object>();args.add(scope.access().employeeId());args.add(scope.access().employeeId());args.addAll(f.args());nearby.hits().forEach(h->args.add(h.id()));
        var rows=jdbc.query("SELECT s.id,s.name,s.address,q.pool_type,q.owner_employee_id,(SELECT MAX(o.effective_at) FROM crm_store_order_fact o WHERE o.tenant_id=q.tenant_id AND o.store_id=q.store_id AND o.salesperson_id=? AND o.valid_order=1) last_order,(SELECT COUNT(*) FROM crm_store_order_fact o WHERE o.tenant_id=q.tenant_id AND o.store_id=q.store_id AND o.salesperson_id=? AND o.valid_order=1) order_count"+BASE+" WHERE "+f.sql()+" AND s.id IN ("+String.join(",",Collections.nCopies(nearby.hits().size(),"?"))+")",
                (r,n)->new Item(r.getString("id"),r.getString("name"),r.getString("address"),"PUBLIC".equals(r.getString("pool_type"))?"PUBLIC":Objects.equals(scope.access().employeeId(),r.getString("owner_employee_id"))?"MINE":"TRANSFERRED",instant(r,"last_order"),r.getLong("order_count")),args.toArray());
        var byId=new HashMap<String,Item>();rows.forEach(r->byId.put(r.id(),r));
        var items=nearby.hits().stream().filter(h->byId.containsKey(h.id())).map(h->new NearbyModels.Item<>(byId.get(h.id()),h.meters(),h.longitude(),h.latitude(),h.coordinateSystem())).toList();
        return new NearbyModels.Page<>(total,items,nearby.nextCursor(),origin);
    }
    @Override public long customerCount(StoreScope scope,String kind,Instant from,Instant to) {
        var f=filter(scope,kind,from,to,null,null);
        return jdbc.queryForObject("SELECT COUNT(*)"+BASE+" WHERE "+f.sql(),Long.class,f.args().toArray());
    }
    @Override public long newContactCount(StoreScope scope,Instant from,Instant to) {
        var f=mine(scope);var args=new ArrayList<>(f.args());
        args.add(scope.access().employeeId());args.add(Timestamp.from(from));args.add(Timestamp.from(to));
        return jdbc.queryForObject("SELECT COUNT(DISTINCT c.contact_id)"+BASE
            +" JOIN crm_store_contact c ON c.tenant_id=q.tenant_id AND c.store_id=q.store_id"
            +" WHERE "+f.sql()+" AND c.status_code='ACTIVE' AND EXISTS (SELECT 1 FROM crm_contact_change a"
            +" WHERE a.tenant_id=c.tenant_id AND a.contact_id=c.contact_id AND a.operation='CREATE'"
            +" AND a.actor_employee_id=? AND a.occurred_at>=? AND a.occurred_at<?)",Long.class,args.toArray());
    }
    @Override public long contactPending(StoreScope scope) {
        var f=mine(scope);return jdbc.queryForObject("SELECT COUNT(*)"+BASE+" WHERE "+f.sql()+" AND "+CONTACT_MISSING,Long.class,f.args().toArray());
    }
    @Override public Contacts contacts(StoreScope scope,boolean newly,Instant from,Instant to,String keyword,String cursor,int size) {
        var f=mine(scope);var args=new ArrayList<>(f.args());
        String where=f.sql()+" AND c.status_code='ACTIVE'";
        if(newly){where+=" AND EXISTS (SELECT 1 FROM crm_contact_change a WHERE a.tenant_id=p.tenant_id AND a.contact_id=p.id AND a.operation='CREATE' AND a.actor_employee_id=? AND a.occurred_at>=? AND a.occurred_at<?)";args.add(scope.access().employeeId());args.add(Timestamp.from(from));args.add(Timestamp.from(to));}
        if(keyword!=null&&!keyword.isBlank()){where+=" AND (LOCATE(?,p.name)>0 OR EXISTS (SELECT 1 FROM crm_contact_method m WHERE m.tenant_id=p.tenant_id AND m.contact_id=p.id AND m.method_type='PHONE' AND LOCATE(?,m.method_value)>0))";args.add(keyword.strip());args.add(keyword.strip());}
        String joins=BASE+" JOIN crm_store_contact c ON c.tenant_id=q.tenant_id AND c.store_id=q.store_id JOIN crm_contact_person p ON p.tenant_id=c.tenant_id AND p.id=c.contact_id ";
        long total=jdbc.queryForObject("SELECT COUNT(DISTINCT p.id)"+joins+" WHERE "+where,Long.class,args.toArray());
        if(cursor!=null&&!cursor.isBlank()){where+=" AND p.id<?";args.add(cursor);}args.add(size+1);
        // Page by person, then load all currently authorized relations in one batch.
        String sql="SELECT d.id,d.name,d.created_at,d.store_id,s.name store_name,c.role_name FROM (SELECT p.id,p.name,p.created_at,MIN(c.store_id) store_id"+joins+" WHERE "+where+" GROUP BY p.id,p.name,p.created_at ORDER BY p.id DESC LIMIT ?) d JOIN crm_store_master s ON s.tenant_id=? AND s.id=d.store_id JOIN crm_store_contact c ON c.tenant_id=s.tenant_id AND c.store_id=s.id AND c.contact_id=d.id ORDER BY d.id DESC";
        args.add(scope.access().tenantId());
        var rows=jdbc.query(sql,(r,n)->new ContactItem(r.getString("id"),r.getString("name"),r.getString("role_name"),r.getString("store_id"),r.getString("store_name"),instant(r,"created_at"),List.of()),args.toArray());
        boolean more=rows.size()>size;var items=more?rows.subList(0,size):rows;
        if(items.isEmpty())return new Contacts(total,List.of(),null);
        var relationArgs=new ArrayList<>(f.args());items.forEach(item->relationArgs.add(item.id()));
        var stores=new HashMap<String,List<ContactStore>>();
        jdbc.query("SELECT c.contact_id,s.id,s.name,c.role_name"+joins+" WHERE "+f.sql()+" AND c.status_code='ACTIVE' AND p.id IN ("+String.join(",",Collections.nCopies(items.size(),"?"))+") ORDER BY s.id",(org.springframework.jdbc.core.RowCallbackHandler)r->stores.computeIfAbsent(r.getString("contact_id"),key->new ArrayList<>()).add(new ContactStore(r.getString("id"),r.getString("name"),r.getString("role_name"))),relationArgs.toArray());
        var result=items.stream().map(item->new ContactItem(item.id(),item.name(),item.role(),item.storeId(),item.storeName(),item.createdAt(),List.copyOf(stores.getOrDefault(item.id(),List.of())))).toList();
        return new Contacts(total,result,more?items.getLast().id():null);
    }
    @Override public Orders orders(StoreScope scope,String storeId,String cursor,int size) {
        var args=new ArrayList<Object>(List.of(scope.access().tenantId(),scope.access().employeeId(),storeId));
        String where="tenant_id=? AND salesperson_id=? AND store_id=? AND valid_order=1";
        String amount="CAST(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.payableAmount')) AS DECIMAL(18,2))";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM crm_store_order_fact WHERE "+where,Long.class,args.toArray());
        if(total==0) {
            var own=mine(scope);var ownArgs=new ArrayList<>(own.args());ownArgs.add(storeId);
            if(jdbc.queryForObject("SELECT COUNT(*)"+BASE+" WHERE "+own.sql()+" AND q.store_id=?",Long.class,ownArgs.toArray())==0)throw new AuthorizationDeniedException("customer-history");
        }
        BigDecimal sum=jdbc.queryForObject("SELECT COALESCE(SUM("+amount+"),0) FROM crm_store_order_fact WHERE "+where,BigDecimal.class,args.toArray());
        if(cursor!=null&&!cursor.isBlank()){where+=" AND order_id<?";args.add(cursor);}args.add(size+1);
        var rows=jdbc.query("SELECT order_id,effective_at,"+amount+" amount FROM crm_store_order_fact WHERE "+where+" ORDER BY order_id DESC LIMIT ?",(r,n)->new OrderItem(r.getString("order_id"),instant(r,"effective_at"),r.getBigDecimal("amount")),args.toArray());
        boolean more=rows.size()>size;var items=more?rows.subList(0,size):rows;
        return new Orders(total,sum,List.copyOf(items),more?items.getLast().id():null);
    }
    private static Instant instant(ResultSet r,String field)throws SQLException {var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
}
