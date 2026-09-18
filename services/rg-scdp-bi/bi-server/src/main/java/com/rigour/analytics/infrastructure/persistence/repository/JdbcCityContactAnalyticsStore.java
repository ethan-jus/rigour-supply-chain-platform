package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.CityContactAnalyticsView.City;
import com.rigour.analytics.application.port.out.CityContactAnalyticsStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SourceRefreshResult;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

/** 仅刷新访问 Sales/CRM；按门店去重后再汇总，禁止把拜访次数当作客户数。 */
@Repository
public class JdbcCityContactAnalyticsStore implements CityContactAnalyticsStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCityContactAnalyticsStore(DataSource dataSource) {
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<String> tenantIds() {
        return jdbc.query(
                "SELECT DISTINCT BIN_TO_UUID(tenant_id) AS tenant_id FROM"
                    + " bi_source_sales_sales_submitted_visit",
                new MapSqlParameterSource(),
                (rs, index) -> rs.getString("tenant_id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SourceRefreshResult refresh(String tenantId, Instant syncedAt) {
        var args =
                new MapSqlParameterSource("tenant", tenantId).addValue("synced", local(syncedAt));
        jdbc.update("DELETE FROM bi_sales_contact_fact WHERE tenant_id = :tenant", args);
        jdbc.update("DELETE FROM bi_sales_contact_city_dim WHERE tenant_id = :tenant", args);
        jdbc.update(
                """
                INSERT INTO bi_sales_contact_city_dim (tenant_id, region_code, city_name)
                SELECT :tenant, area_code, area_name FROM bi_source_crm_crm_customer_area
                 WHERE tenant_id = UUID_TO_BIN(:tenant) AND deleted = 0 AND status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM bi_source_crm_crm_customer_area child
                        WHERE child.tenant_id = bi_source_crm_crm_customer_area.tenant_id
                          AND child.parent_area_code = bi_source_crm_crm_customer_area.area_code
                          AND child.deleted = 0 AND child.status = 'ACTIVE')
                """,
                args);
        int count =
                jdbc.update(
                        """
INSERT INTO bi_sales_contact_fact (tenant_id, submission_id, store_id, city_name,
    region_code, submitted_at, review_status, salesperson_id, owner_staff_code, customer_id, customer_code)
SELECT :tenant,BIN_TO_UUID(s.id),BIN_TO_UUID(s.store_id),s.city_name,a.region_code,
    s.submitted_at,s.review_status,BIN_TO_UUID(s.salesperson_id),s.owner_staff_code,s.customer_id,s.customer_code
  FROM bi_source_sales_sales_submitted_visit s
  LEFT JOIN (SELECT TRIM(city_name) city_name,MIN(region_code) region_code
        FROM bi_sales_contact_city_dim WHERE tenant_id=:tenant
        GROUP BY TRIM(city_name) HAVING COUNT(*)=1) a ON a.city_name=s.city_name
 WHERE s.tenant_id=UUID_TO_BIN(:tenant)
""",
                        args);
        jdbc.update("DELETE FROM bi_sales_contact_snapshot WHERE tenant_id = :tenant", args);
        jdbc.update(
                "INSERT INTO bi_sales_contact_snapshot (tenant_id, synced_time,"
                    + " business_links_ready) VALUES (:tenant, :synced, 1)",
                args);
        return new SourceRefreshResult(
                "SALES_SUBMITTED_VISIT", "Sales已提交拜访", (long) count, (long) count, 0L, syncedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot read(
            String tenantId, Instant from, Instant to, String regionCode, String ownerStaffCode) {
        var args =
                new MapSqlParameterSource("tenant", tenantId)
                        .addValue("from", local(from))
                        .addValue("to", local(to))
                        .addValue("region", regionCode)
                        .addValue("owner", ownerStaffCode);
        var snapshots =
                jdbc.query(
                        "SELECT synced_time, business_links_ready FROM bi_sales_contact_snapshot"
                            + " WHERE tenant_id = :tenant",
                        args,
                        (rs, index) ->
                                new Snapshot(
                                        rs.getObject("synced_time", LocalDateTime.class)
                                                .toInstant(ZoneOffset.UTC),
                                        rs.getBoolean("business_links_ready"),
                                        List.of()));
        if (snapshots.isEmpty()) return new Snapshot(null, false, List.of());
        var snapshot = snapshots.getFirst();
        if (ownerStaffCode != null && !snapshot.businessLinksReady()) return snapshot;
        var rows =
                com.rigour.analytics.infrastructure.persistence.scope.BiScopedQueries.query(
                        jdbc,
                        """
WITH stores AS (
    SELECT region_code, city_name, store_id,
           MAX(CASE WHEN review_status = 'APPROVED' THEN 1 ELSE 0 END) approved,
           MAX(CASE WHEN review_status = 'FLAGGED' THEN 1 ELSE 0 END) flagged,
           MAX(CASE WHEN customer_code IS NOT NULL THEN 1 ELSE 0 END) linked
      FROM bi_sales_contact_fact
     WHERE tenant_id = :tenant AND submitted_at >= :from AND submitted_at <= :to
       AND (:region IS NULL OR region_code = :region)
       AND (:owner IS NULL OR owner_staff_code = :owner)
     GROUP BY region_code, city_name, store_id
), totals AS (
    SELECT region_code, city_name, COUNT(*) contacted,
           SUM(CASE WHEN flagged = 0 THEN approved ELSE 0 END) approved, SUM(flagged) flagged, SUM(linked) linked
      FROM stores GROUP BY region_code, city_name
), result AS (
    SELECT c.region_code, c.city_name, COALESCE(t.contacted, 0) contacted,
           COALESCE(t.approved, 0) approved, COALESCE(t.flagged, 0) flagged, COALESCE(t.linked, 0) linked
      FROM bi_sales_contact_city_dim c LEFT JOIN totals t ON t.region_code = c.region_code
     WHERE c.tenant_id = :tenant AND (:region IS NULL OR c.region_code = :region)
    UNION ALL
    SELECT region_code, city_name, contacted, approved, flagged, linked FROM totals WHERE region_code IS NULL
)
SELECT * FROM result ORDER BY contacted DESC, city_name
""",
                        args,
                        (rs, index) ->
                                new City(
                                        rs.getString("region_code"),
                                        rs.getString("city_name"),
                                        rs.getLong("contacted"),
                                        rs.getLong("approved"),
                                        rs.getLong("contacted")
                                                - rs.getLong("approved")
                                                - rs.getLong("flagged"),
                                        rs.getLong("flagged"),
                                        snapshot.businessLinksReady() ? rs.getLong("linked") : null,
                                        snapshot.businessLinksReady()
                                                ? rs.getLong("contacted") - rs.getLong("linked")
                                                : null));
        return new Snapshot(snapshot.syncedAt(), snapshot.businessLinksReady(), rows);
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
