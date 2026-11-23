package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesWorkVisitPlanRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import org.springframework.stereotype.Repository;

/** sales_visit_plan 与 CRM 门店投影的 JDBC 实现。 */
@Repository
public class JdbcSalesWorkVisitPlanRepository implements SalesWorkVisitPlanRepository {

    private static final String PLAN_SELECT = """
            SELECT plan.id, plan.sales_profile_id, profile.sales_no, plan.planned_date,
                   plan.target_type, plan.customer_id, plan.store_id,
                   store.customer_name,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(plan.target_snapshot_json,'$.name')),store.store_name) AS store_name,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(plan.target_snapshot_json,'$.address')),store.store_address) AS store_address,
                   COALESCE(JSON_EXTRACT(plan.target_snapshot_json,'$.longitude'),store.longitude) AS longitude,
                   COALESCE(JSON_EXTRACT(plan.target_snapshot_json,'$.latitude'),store.latitude) AS latitude,
                   plan.crm_store_id, plan.action_code, plan.objective, plan.status,
                   visit.id AS visit_id, plan.version, plan.created_by,
                   plan.created_at, plan.updated_at
              FROM sales_visit_plan plan
              JOIN sales_profile profile
                ON profile.tenant_id=plan.tenant_id AND profile.id=plan.sales_profile_id
              LEFT JOIN crm_store_projection store
                ON store.tenant_id=plan.tenant_id AND store.store_id=plan.store_id
              LEFT JOIN sales_visit visit
                ON visit.tenant_id=plan.tenant_id AND visit.visit_plan_id=plan.id
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private static String allowed(Access scope) {
        return scope.all() ? "" : scope.employeeCodes().isEmpty() ? " AND 1=0" : " AND profile.hr_employee_code IN (:codes)";
    }
    private static MapSqlParameterSource params(UUID tenant,Access scope) {
        return new MapSqlParameterSource("tenant",bin(tenant)).addValue("codes",scope.employeeCodes());
    }
    @Override public boolean canManageProfile(UUID tenant,UUID id,Access scope) {
        if(scope.all())return true;
        return Boolean.TRUE.equals(named.queryForObject("SELECT EXISTS(SELECT 1 FROM sales_profile profile WHERE profile.tenant_id=:tenant AND profile.id=:id"+allowed(scope)+")",
                params(tenant,scope).addValue("id",bin(id)),Boolean.class));
    }

    public JdbcSalesWorkVisitPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public List<VisitPlanRow> findOwnPlans(UUID tenantId, UUID salesProfileId, LocalDate date) {
        return jdbc.query(PLAN_SELECT + """
                 WHERE plan.tenant_id=? AND plan.sales_profile_id=? AND plan.planned_date=?
                   AND plan.status<>'CANCELLED'
                 ORDER BY CASE plan.status WHEN 'IN_PROGRESS' THEN 0 WHEN 'PLANNED' THEN 1 ELSE 2 END,
                          plan.created_at, plan.id
                """, (rs, row) -> plan(rs), bin(tenantId), bin(salesProfileId), Date.valueOf(date));
    }

    @Override
    public Optional<VisitPlanRow> findOwnPlan(
            UUID tenantId, UUID salesProfileId, UUID planId, boolean lock) {
        String sql = PLAN_SELECT + """
                 WHERE plan.tenant_id=? AND plan.sales_profile_id=? AND plan.id=?
                 LIMIT 1
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> plan(rs),
                bin(tenantId), bin(salesProfileId), bin(planId)).stream().findFirst();
    }

    private static final String MANAGEMENT_FILTER="""
            WHERE plan.tenant_id=:tenant AND plan.planned_date BETWEEN :from AND :to
              AND (plan.action_code IS NULL OR plan.action_code='VISIT')
              AND (:status IS NULL OR plan.status=:status)
            """;

    @Override
    public List<VisitPlanRow> findManagementPlans(UUID tenant,LocalDate from,LocalDate to,String status,int limit,int offset,Access access) {
        return named.query(PLAN_SELECT+MANAGEMENT_FILTER+allowed(access)+" ORDER BY plan.planned_date DESC,plan.created_at DESC,plan.id DESC LIMIT :limit OFFSET :offset",
                params(tenant,access).addValue("from",from).addValue("to",to).addValue("status",status).addValue("limit",limit).addValue("offset",offset),(rs,n)->plan(rs));
    }
    @Override
    public long countManagementPlans(UUID tenant,LocalDate from,LocalDate to,String status,Access access) {
        return named.queryForObject("SELECT COUNT(*) FROM sales_visit_plan plan JOIN sales_profile profile ON profile.tenant_id=plan.tenant_id AND profile.id=plan.sales_profile_id "
            +MANAGEMENT_FILTER+allowed(access), params(tenant,access).addValue("from",from).addValue("to",to).addValue("status",status),Long.class);
    }

    @Override
    public Optional<VisitPlanRow> findManagementPlan(UUID tenantId, UUID planId, boolean lock) {
        String sql = PLAN_SELECT + """
                 WHERE plan.tenant_id=? AND plan.id=?
                 LIMIT 1
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> plan(rs), bin(tenantId), bin(planId)).stream().findFirst();
    }

    @Override
    public List<ProfileOptionRow> findActiveProfiles(UUID tenant,Access access) {
        return named.query("SELECT id,employee_id,sales_no,city_org_id FROM sales_profile profile WHERE tenant_id=:tenant AND status='ACTIVE'"+allowed(access)+" ORDER BY sales_no,id",
                params(tenant,access),(r,n)->new ProfileOptionRow(uuid(r,"id"),uuid(r,"employee_id"),r.getString("sales_no"),uuid(r,"city_org_id")));
    }

    @Override
    public Optional<ProfileOptionRow> findActiveProfile(UUID tenantId, UUID salesProfileId) {
        return jdbc.query("""
                SELECT id, employee_id, sales_no, city_org_id
                  FROM sales_profile
                 WHERE tenant_id=? AND id=? AND status='ACTIVE'
                 LIMIT 1
                """, (rs, row) -> new ProfileOptionRow(uuid(rs, "id"), uuid(rs, "employee_id"),
                rs.getString("sales_no"), uuid(rs, "city_org_id")),
                bin(tenantId), bin(salesProfileId)).stream().findFirst();
    }

    @Override
    public boolean existsActiveDuplicate(
            UUID tenantId, UUID salesProfileId, LocalDate plannedDate, UUID storeId, UUID excludedPlanId) {
        Integer found = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sales_visit_plan
                 WHERE tenant_id=? AND sales_profile_id=? AND planned_date=? AND store_id=?
                   AND status IN ('PLANNED','IN_PROGRESS')
                   AND (? IS NULL OR id<>?))
                """, Integer.class, bin(tenantId), bin(salesProfileId), Date.valueOf(plannedDate),
                bin(storeId), bin(excludedPlanId), bin(excludedPlanId));
        return found != null && found == 1;
    }

    @Override
    public void insertPlan(UUID id, UUID tenantId, UUID salesProfileId, LocalDate plannedDate,
                           UUID customerId, UUID storeId, String objective, UUID createdBy, Instant now) {
        jdbc.update("""
                INSERT INTO sales_visit_plan
                    (id, tenant_id, sales_profile_id, planned_date, target_type,
                     customer_id, store_id, crm_candidate_id, objective, status,
                     created_by, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MY_STORE', ?, ?, NULL, ?, 'PLANNED', ?, 0, ?, ?)
                """, bin(id), bin(tenantId), bin(salesProfileId), Date.valueOf(plannedDate),
                bin(customerId), bin(storeId), objective, bin(createdBy), timestamp(now), timestamp(now));
    }

    @Override
    public int updatePlannedPlan(UUID tenantId, UUID planId, long expectedVersion,
                                 UUID salesProfileId, LocalDate plannedDate,
                                 UUID customerId, UUID storeId, String objective, Instant now) {
        return jdbc.update("""
                UPDATE sales_visit_plan
                   SET sales_profile_id=?, planned_date=?, target_type='MY_STORE',
                       customer_id=?, store_id=?, crm_candidate_id=NULL, objective=?,
                       version=version+1, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='PLANNED' AND version=?
                """, bin(salesProfileId), Date.valueOf(plannedDate), bin(customerId), bin(storeId),
                objective, timestamp(now), bin(tenantId), bin(planId), expectedVersion);
    }

    @Override
    public int cancelPlannedPlan(UUID tenantId, UUID planId, long expectedVersion, Instant now) {
        return jdbc.update("""
                UPDATE sales_visit_plan
                   SET status='CANCELLED', version=version+1, updated_at=?
                 WHERE tenant_id=? AND id=? AND status='PLANNED' AND version=?
                """, timestamp(now), bin(tenantId), bin(planId), expectedVersion);
    }

    @Override
    public int completeOwnAction(UUID tenantId, UUID salesProfileId, UUID planId, Instant now) {
        return jdbc.update("UPDATE sales_visit_plan SET status='COMPLETED',version=version+1,updated_at=? WHERE tenant_id=? AND sales_profile_id=? AND id=? AND status='PLANNED' AND source_capture_id IS NOT NULL AND action_code<>'VISIT'",
                timestamp(now),bin(tenantId),bin(salesProfileId),bin(planId));
    }

    @Override
    public int markInProgress(UUID tenantId, UUID salesProfileId, UUID planId, Instant now) {
        return jdbc.update("""
                UPDATE sales_visit_plan
                   SET status='IN_PROGRESS', version=version+1, updated_at=?
                 WHERE tenant_id=? AND sales_profile_id=? AND id=? AND status='PLANNED'
                """, timestamp(now), bin(tenantId), bin(salesProfileId), bin(planId));
    }

    @Override
    public int markCompletedByVisit(UUID tenantId, UUID visitId, Instant now) {
        return jdbc.update("""
                UPDATE sales_visit_plan plan
                  JOIN sales_visit visit
                    ON visit.tenant_id=plan.tenant_id AND visit.visit_plan_id=plan.id
                   SET plan.status='COMPLETED', plan.version=plan.version+1, plan.updated_at=?
                 WHERE plan.tenant_id=? AND visit.id=? AND plan.status='IN_PROGRESS'
                """, timestamp(now), bin(tenantId), bin(visitId));
    }

    @Override
    public Optional<String> findStatusByVisit(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT plan.status
                  FROM sales_visit visit
                  JOIN sales_visit_plan plan
                    ON plan.tenant_id=visit.tenant_id AND plan.id=visit.visit_plan_id
                 WHERE visit.tenant_id=? AND visit.id=?
                 LIMIT 1
                """, (rs, row) -> rs.getString("status"), bin(tenantId), bin(visitId))
                .stream().findFirst();
    }

    @Override
    public Optional<UUID> findPlanIdByVisit(UUID tenantId, UUID visitId) {
        return jdbc.query("""
                SELECT visit.visit_plan_id
                  FROM sales_visit visit
                 WHERE visit.tenant_id=? AND visit.id=? AND visit.visit_plan_id IS NOT NULL
                 LIMIT 1
                """, (rs, row) -> uuid(rs, "visit_plan_id"), bin(tenantId), bin(visitId))
                .stream().findFirst();
    }

    private static VisitPlanRow plan(ResultSet rs) throws SQLException {
        return new VisitPlanRow(uuid(rs, "id"), uuid(rs, "sales_profile_id"),
                rs.getString("sales_no"), rs.getDate("planned_date").toLocalDate(),
                rs.getString("target_type"), uuid(rs, "customer_id"), uuid(rs, "store_id"),
                rs.getString("customer_name"), rs.getString("store_name"),
                rs.getString("store_address"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"), rs.getString("objective"), rs.getString("status"),
                uuid(rs, "visit_id"), rs.getLong("version"), uuid(rs, "created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getString("crm_store_id"), rs.getString("action_code"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return SalesUuidCodec.decode(rs.getBytes(column));
    }

    private static byte[] bin(UUID value) {
        return value == null ? null : SalesUuidCodec.encode(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
