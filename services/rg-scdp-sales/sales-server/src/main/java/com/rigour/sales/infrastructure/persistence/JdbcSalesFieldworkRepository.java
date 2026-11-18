package com.rigour.sales.infrastructure.persistence;
import com.rigour.sales.application.port.out.SalesFieldworkRepository;
import com.rigour.sales.api.v1.model.SalesFieldworkModels.*;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class JdbcSalesFieldworkRepository implements SalesFieldworkRepository {
    private final JdbcTemplate jdbc;
    public JdbcSalesFieldworkRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<Pending> pendingSummaries(int limit) {
        return jdbc.query("""
            SELECT d.tenant_id,d.id,d.version,d.business_date,d.field_policy_version_id,d.sales_profile_id
            FROM sales_work_day d LEFT JOIN sales_fieldwork_day_metrics m ON m.tenant_id=d.tenant_id AND m.work_day_id=d.id
            WHERE m.work_day_id IS NULL OR m.source_version<d.version ORDER BY d.updated_at DESC LIMIT ?
            """, (r,n)->new Pending(uuid(r.getBytes(1)),uuid(r.getBytes(2)),r.getLong(3),r.getObject(4,LocalDate.class),uuid(r.getBytes(5)),uuid(r.getBytes(6))),limit);
    }
    @Override public void saveSummary(Pending d,Integer distance,int minutes,int gaps,int rejected,Instant at) {
        // A concurrent newer calculation wins; never turn an older scan into the newest result.
        jdbc.update("""
            INSERT INTO sales_fieldwork_day_metrics (tenant_id,work_day_id,source_version,business_date,distance_meters,tracked_minutes,gap_count,rejected_point_count,calculated_at)
            VALUES (?,?,?,?,?,?,?,?,?) AS incoming
            ON DUPLICATE KEY UPDATE
            distance_meters=IF(incoming.source_version>=sales_fieldwork_day_metrics.source_version,incoming.distance_meters,sales_fieldwork_day_metrics.distance_meters),
            tracked_minutes=IF(incoming.source_version>=sales_fieldwork_day_metrics.source_version,incoming.tracked_minutes,sales_fieldwork_day_metrics.tracked_minutes),
            gap_count=IF(incoming.source_version>=sales_fieldwork_day_metrics.source_version,incoming.gap_count,sales_fieldwork_day_metrics.gap_count),
            rejected_point_count=IF(incoming.source_version>=sales_fieldwork_day_metrics.source_version,incoming.rejected_point_count,sales_fieldwork_day_metrics.rejected_point_count),
            calculated_at=IF(incoming.source_version>=sales_fieldwork_day_metrics.source_version,incoming.calculated_at,sales_fieldwork_day_metrics.calculated_at),
            source_version=GREATEST(sales_fieldwork_day_metrics.source_version,incoming.source_version)
            """,bin(d.tenantId()),bin(d.workDayId()),d.version(),d.date(),distance,minutes,gaps,rejected,Timestamp.from(at));
    }
    private static final String FROM="""
        FROM sales_profile p
        LEFT JOIN sales_work_day d ON d.tenant_id=p.tenant_id AND d.sales_profile_id=p.id AND d.business_date=?
        LEFT JOIN sales_fieldwork_day_metrics m ON m.tenant_id=d.tenant_id AND m.work_day_id=d.id
        LEFT JOIN (
            SELECT v.tenant_id,v.sales_profile_id,COUNT(*) visit_count
            FROM sales_visit v JOIN sales_work_day day ON day.tenant_id=v.tenant_id AND day.id=v.work_day_id
            WHERE v.status<>'CANCELLED' AND v.tenant_id=? AND day.business_date=? GROUP BY v.tenant_id,v.sales_profile_id
        ) v ON v.tenant_id=p.tenant_id AND v.sales_profile_id=p.id
        LEFT JOIN (
            SELECT c.tenant_id,c.work_day_id,COUNT(*) pending_count FROM sales_fieldwork_case c
            JOIN sales_work_day reviewed_day ON reviewed_day.tenant_id=c.tenant_id AND reviewed_day.id=c.work_day_id
            WHERE c.tenant_id=? AND reviewed_day.business_date=? AND c.status IN ('OPEN','AWAITING_STATEMENT','EXPLAINED')
            GROUP BY c.tenant_id,c.work_day_id
        ) review ON review.tenant_id=d.tenant_id AND review.work_day_id=d.id
        WHERE p.tenant_id=? AND p.status='ACTIVE' 
        """;
    record Filter(String sql,List<Object> args) {}
    private Filter filter(UUID tenant,LocalDate date,Access scope,Search search) {
        var args=new ArrayList<Object>(List.of(date,bin(tenant),date,bin(tenant),date,bin(tenant)));
        var sql=new StringBuilder(FROM);
        if(!scope.all()) {
            if(scope.employeeCodes().isEmpty()) sql.append(" AND 1=0");
            else {sql.append(" AND p.hr_employee_code IN (").append(String.join(",",Collections.nCopies(scope.employeeCodes().size(),"?"))).append(")");args.addAll(scope.employeeCodes());}
        }
        if(search.status()!=null&&!search.status().isBlank()) {sql.append(" AND COALESCE(d.status,'NOT_STARTED')=?");args.add(search.status());}
        if(search.keyword()!=null&&!search.keyword().isBlank()) {
            sql.append(" AND (p.sales_no LIKE ? OR p.hr_employee_code LIKE ?");args.add("%"+search.keyword()+"%");args.add("%"+search.keyword()+"%");
            if(!search.nameMatches().isEmpty()) {sql.append(" OR p.hr_employee_code IN (").append(String.join(",",Collections.nCopies(search.nameMatches().size(),"?"))).append(")");args.addAll(search.nameMatches());}
            sql.append(")");
        }
        if(search.pendingOnly()) sql.append(" AND COALESCE(review.pending_count,0)>0");
        if(search.riskKind()!=null&&!search.riskKind().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM sales_fieldwork_case risk WHERE risk.tenant_id=d.tenant_id AND risk.work_day_id=d.id AND risk.kind=? AND risk.status IN ('OPEN','AWAITING_STATEMENT','EXPLAINED'))");args.add(search.riskKind());
        }
        return new Filter(sql.toString(),args);
    }
    @Override public List<PersonDay> page(UUID tenant,LocalDate date,Access scope,Search search,int limit,int offset) {
        var f=filter(tenant,date,scope,search);var args=new ArrayList<>(f.args());args.add(limit);args.add(offset);
        return jdbc.query("""
            SELECT p.id,p.hr_employee_code,p.sales_no,d.id,COALESCE(d.status,'NOT_STARTED') status,
                d.checked_in_at,d.checked_out_at,COALESCE(d.verified_work_minutes,0) minutes,
                m.distance_meters,m.gap_count,COALESCE(v.visit_count,0) visits,COALESCE(review.pending_count,0) pending,
                d.id IS NOT NULL AND (m.work_day_id IS NULL OR m.source_version<d.version) summary_pending
            """+f.sql()+" ORDER BY COALESCE(review.pending_count,0) DESC,COALESCE(m.gap_count,0) DESC,p.sales_no,p.id LIMIT ? OFFSET ?",
            (r,n)->new PersonDay(uuid(r.getBytes(1)),r.getString(2),r.getString(3),null,uuid(r.getBytes(4)),r.getString(5),instant(r.getTimestamp(6)),instant(r.getTimestamp(7)),r.getInt(8),(Integer)r.getObject(9),(Integer)r.getObject(10),r.getInt(11),r.getInt(12),r.getBoolean(13)),args.toArray());
    }
    @Override public Totals totals(UUID tenant,LocalDate date,Access scope,Search search) {
        var f=filter(tenant,date,scope,search);
        return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(d.status='ACTIVE'),0),COALESCE(SUM(d.id IS NULL),0),COALESCE(SUM(d.status='FINISHED'),0),COALESCE(SUM(m.source_version=d.version AND (m.gap_count>0 OR m.rejected_point_count>0)),0),COALESCE(SUM(review.pending_count>0),0) "+f.sql(),
            (r,n)->new Totals(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6)),f.args().toArray());
    }
    @Override public List<String> employeeCodes(UUID tenant,Access scope) {
        var args=new ArrayList<Object>();args.add(bin(tenant));
        String sql="SELECT hr_employee_code FROM sales_profile WHERE tenant_id=? AND status='ACTIVE' AND hr_employee_code IS NOT NULL";
        if(!scope.all()) {
            if(scope.employeeCodes().isEmpty()) return List.of();
            sql+=" AND hr_employee_code IN ("+String.join(",",Collections.nCopies(scope.employeeCodes().size(),"?"))+")";args.addAll(scope.employeeCodes());
        }
        return jdbc.query(sql+" ORDER BY hr_employee_code",(r,n)->r.getString(1),args.toArray());
    }
    @Override public Optional<String> profileEmployeeCode(UUID tenant,UUID profile) {
        return jdbc.query("SELECT hr_employee_code FROM sales_profile WHERE tenant_id=? AND id=?",(r,n)->r.getString(1),bin(tenant),bin(profile)).stream().filter(Objects::nonNull).findFirst();
    }
    @Override public Optional<String> visitEmployeeCode(UUID tenant,UUID visit) {
        return jdbc.query("SELECT p.hr_employee_code FROM sales_profile p JOIN sales_visit v ON v.tenant_id=p.tenant_id AND v.sales_profile_id=p.id WHERE v.tenant_id=? AND v.id=?",(r,n)->r.getString(1),bin(tenant),bin(visit)).stream().filter(Objects::nonNull).findFirst();
    }
    private static byte[] bin(UUID value) { return SalesUuidCodec.encode(value); }
    private static UUID uuid(byte[] value) { return value==null?null:SalesUuidCodec.decode(value); }
    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
}
