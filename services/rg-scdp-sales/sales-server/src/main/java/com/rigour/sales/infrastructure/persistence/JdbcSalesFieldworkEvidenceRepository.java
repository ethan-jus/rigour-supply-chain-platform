package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.*;
import com.rigour.sales.application.port.out.SalesFieldworkEvidenceRepository;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Repository
public class JdbcSalesFieldworkEvidenceRepository implements SalesFieldworkEvidenceRepository {
    private final JdbcTemplate jdbc;
    public JdbcSalesFieldworkEvidenceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<DeviceEvent> devices(UUID tenant, UUID profile, UUID day) {
        return jdbc.query("""
            SELECT e.id,e.event_type,e.client_occurred_at,e.server_received_at,
                IF(NULLIF(TRIM(e.device_id_hash),'') IS NULL,NULL,LEFT(SHA2(e.device_id_hash,256),16)) installation_reference,
                (SELECT MIN(old.server_received_at) FROM sales_punch_event old
                 JOIN sales_work_day d ON d.tenant_id=old.tenant_id AND d.id=old.work_day_id
                 WHERE old.tenant_id=e.tenant_id AND d.sales_profile_id=? AND old.device_id_hash=e.device_id_hash
                   AND NULLIF(TRIM(old.device_id_hash),'') IS NOT NULL AND old.server_received_at<=e.server_received_at) first_seen,
                e.evidence_status
            FROM sales_punch_event e JOIN sales_work_day d ON d.tenant_id=e.tenant_id AND d.id=e.work_day_id
            WHERE e.tenant_id=? AND d.sales_profile_id=? AND e.work_day_id=?
            ORDER BY e.server_received_at,e.id
            """,(r,n)->new DeviceEvent(uuid(r.getBytes(1)),r.getString(2),at(r.getTimestamp(3)),at(r.getTimestamp(4)),r.getString(5),at(r.getTimestamp(6)),r.getString(7)),bin(profile),bin(tenant),bin(profile),bin(day));
    }
    private static final String CAPTURE="""
        SELECT v.id,v.sales_profile_id,v.work_day_id,d.business_date,v.crm_store_id,v.crm_visit_id,
            s.store_name,v.checked_in_at,v.checked_out_at,v.status,v.result_note,v.contact_outcome,v.intention_level
        """;
    private static final String FROM="""
        FROM sales_visit v JOIN sales_work_day d ON d.tenant_id=v.tenant_id AND d.id=v.work_day_id
        JOIN sales_profile p ON p.tenant_id=v.tenant_id AND p.id=v.sales_profile_id
        LEFT JOIN sales_visit_target_snapshot s ON s.tenant_id=v.tenant_id AND s.visit_id=v.id
        """;
    @Override public Optional<Capture> capture(UUID tenant, UUID visit) {
        return jdbc.query(CAPTURE+FROM+" WHERE v.tenant_id=? AND v.id=?",(r,n)->capture(r),bin(tenant),bin(visit)).stream().findFirst();
    }
    @Override public VisitHistory history(UUID tenant,Capture current,Access scope,int page,int size) {
        // Only the formal CRM store identity is comparable; legacy names/UUIDs are never guessed.
        if(current.crmStoreId()==null || current.checkedInAt()==null)
            return new VisitHistory(false,current,0,0,0,0,0,0,List.of(),0,page,size);
        var args=new ArrayList<Object>(List.of(bin(tenant),current.crmStoreId(),current.businessDate().minusDays(29),
                current.businessDate(),Timestamp.from(current.checkedInAt())));
        var where=new StringBuilder(" WHERE v.tenant_id=? AND v.crm_store_id=? AND d.business_date BETWEEN ? AND ? AND v.checked_in_at<?");
        where.append(" AND v.status<>'CANCELLED'");
        if(!scope.all()) {
            if(scope.employeeCodes().isEmpty()) where.append(" AND 1=0");
            else {where.append(" AND p.hr_employee_code IN (").append(String.join(",",Collections.nCopies(scope.employeeCodes().size(),"?"))).append(")");args.addAll(scope.employeeCodes());}
        }
        var countArgs=new ArrayList<Object>(List.of(bin(current.salesProfileId()),current.businessDate().minusDays(6),
                bin(current.salesProfileId()),current.businessDate().minusDays(6),bin(current.salesProfileId()),bin(current.salesProfileId())));
        countArgs.addAll(args);
        long[] counts=jdbc.queryForObject("""
            SELECT COALESCE(SUM(v.sales_profile_id=? AND d.business_date>=?),0),
                COUNT(DISTINCT IF(v.sales_profile_id=? AND d.business_date>=?,d.business_date,NULL)),
                COALESCE(SUM(v.sales_profile_id=?),0),COUNT(DISTINCT IF(v.sales_profile_id=?,d.business_date,NULL)),
                COUNT(*),COUNT(DISTINCT d.business_date)
            """+FROM+where,(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6)},countArgs.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=jdbc.query(CAPTURE+FROM+where+" ORDER BY v.checked_in_at DESC,v.id LIMIT ? OFFSET ?",(r,n)->capture(r),args.toArray());
        return new VisitHistory(true,current,counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],rows,counts[4],page,size);
    }
    private static Capture capture(ResultSet r) throws SQLException {
        return new Capture(uuid(r.getBytes(1)),uuid(r.getBytes(2)),uuid(r.getBytes(3)),r.getObject(4,LocalDate.class),
                (Long)r.getObject(5),(Long)r.getObject(6),r.getString(7),at(r.getTimestamp(8)),at(r.getTimestamp(9)),r.getString(10),r.getString(11),r.getString(12),r.getString(13));
    }
    private static byte[] bin(UUID id) {return SalesUuidCodec.encode(id);}
    private static UUID uuid(byte[] id) {return SalesUuidCodec.decode(id);}
    private static Instant at(Timestamp value) {return value==null?null:value.toInstant();}
}
