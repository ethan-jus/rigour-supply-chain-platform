package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.api.v1.model.FieldworkReport.*;
import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted;
import tools.jackson.databind.ObjectMapper;
import com.rigour.sales.application.port.out.FieldworkFacts;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class JdbcFieldworkFacts implements FieldworkFacts {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcFieldworkFacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static UUID uuid(ResultSet r, String column) throws SQLException {
        var b = r.getBytes(column);
        return b == null ? null : SalesUuidCodec.decode(b);
    }

    private static Instant time(ResultSet r, String column) throws SQLException {
        var t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static MapSqlParameterSource args(UUID tenant, LocalDate date, Access scope) {
        return new MapSqlParameterSource("tenant", SalesUuidCodec.encode(tenant))
                .addValue("date", date)
                .addValue("codes", scope.employeeCodes());
    }

    private static String scope(Access scope) {
        return scope.all()
                ? ""
                : scope.employeeCodes().isEmpty()
                        ? " AND 1=0"
                        : " AND p.hr_employee_code IN (:codes)";
    }

    private static final String DAY =
            " JOIN sales_work_day d ON d.tenant_id=p.tenant_id AND d.sales_profile_id=p.id ";

    public List<Profile> roster(UUID tenant, LocalDate date, Access scope) {
        var z = ZoneId.of("Asia/Shanghai");
        var param =
                args(tenant, date, scope)
                        .addValue("begin", Timestamp.from(date.atStartOfDay(z).toInstant()))
                        .addValue(
                                "end",
                                Timestamp.from(date.plusDays(1).atStartOfDay(z).toInstant()));
        return jdbc.query(
                """
SELECT p.id,p.sales_no,p.hr_employee_id,p.hr_employee_code,d.id day_id,d.status,d.timezone_id,f.business_day_cutoff,
  m.distance_meters,m.gap_count,d.id IS NOT NULL AND (m.work_day_id IS NULL OR m.source_version<d.version) pending
FROM sales_profile p LEFT JOIN sales_work_day d ON d.tenant_id=p.tenant_id AND d.sales_profile_id=p.id AND d.business_date=:date
LEFT JOIN sales_field_policy_version f ON f.tenant_id=d.tenant_id AND f.id=d.field_policy_version_id
LEFT JOIN sales_fieldwork_day_metrics m ON m.tenant_id=d.tenant_id AND m.work_day_id=d.id AND m.source_version=d.version
WHERE p.tenant_id=:tenant AND (d.id IS NOT NULL OR (p.effective_from<:end AND (p.effective_to IS NULL OR p.effective_to>:begin)
  AND (p.status='ACTIVE' OR p.effective_to IS NOT NULL)))
"""
                        + scope(scope),
                param,
                (r, n) -> {
                    var cutoff = r.getObject("business_day_cutoff", LocalTime.class);
                    return new Profile(
                            uuid(r, "id"),
                            r.getString("hr_employee_code"),
                            r.getString("sales_no"),
                            r.getString("hr_employee_id"),
                            uuid(r, "day_id"),
                            Objects.toString(r.getString("status"), "NOT_STARTED"),
                            cutoff == null
                                    ? null
                                    : date.plusDays(1)
                                            .atTime(cutoff)
                                            .atZone(ZoneId.of(r.getString("timezone_id")))
                                            .toInstant(),
                            (Integer) r.getObject("distance_meters"),
                            (Integer) r.getObject("gap_count"),
                            r.getBoolean("pending"));
                });
    }

    public List<Commitment> commitments(
            UUID tenant, Access access, String status, LocalDate from, LocalDate through,
            String ownerCode, int limit, int offset) {
        var args =
                new MapSqlParameterSource("tenant", SalesUuidCodec.encode(tenant))
                        .addValue("codes", access.employeeCodes())
                        .addValue("status", status)
                        .addValue("from", from)
                        .addValue("through", through)
                        .addValue("ownerCode", ownerCode)
                        .addValue("limit", limit)
                        .addValue("offset", offset);
        return jdbc.query(
                """
SELECT a.id,p.id profile,p.hr_employee_code,p.sales_no,JSON_UNQUOTE(JSON_EXTRACT(a.target_snapshot_json,'$.name')) store,
       a.planned_date,a.status,a.source_capture_id,d.business_date,a.updated_at,a.crm_store_id
FROM sales_visit_plan a JOIN sales_profile p ON p.tenant_id=a.tenant_id AND p.id=a.sales_profile_id
JOIN sales_visit v ON v.tenant_id=a.tenant_id AND v.id=a.source_capture_id
JOIN sales_work_day d ON d.tenant_id=v.tenant_id AND d.id=v.work_day_id
WHERE a.tenant_id=:tenant AND a.action_code='RESTOCK' AND a.status=:status
  AND (:ownerCode IS NULL OR p.hr_employee_code=:ownerCode)
  AND (:from IS NULL OR d.business_date>=:from) AND (:through IS NULL OR d.business_date<=:through)
"""
                        + scope(access)
                        + " ORDER BY a.planned_date,a.id LIMIT :limit OFFSET :offset",
                args,
                (r, n) ->
                        new Commitment(
                                uuid(r, "id"),
                                uuid(r, "profile"),
                                r.getString("hr_employee_code"),
                                r.getString("sales_no"),
                                r.getString("store"),
                                r.getObject("planned_date", LocalDate.class),
                                r.getString("status"),
                                uuid(r, "source_capture_id"),
                                r.getObject("business_date", LocalDate.class),
                                time(r, "updated_at"),
                                r.getString("crm_store_id"),
                                null));
    }

    public List<StaffPunch> punches(UUID tenant, LocalDate date, Access scope) {
        return jdbc.query(
                "SELECT p.id,b.event_type,b.client_occurred_at,b.server_received_at FROM"
                    + " sales_profile p"
                        + DAY
                        + """
JOIN sales_punch_event b ON b.tenant_id=d.tenant_id AND b.work_day_id=d.id
WHERE p.tenant_id=:tenant AND d.business_date=:date AND b.event_type IN ('CHECK_IN','CHECK_OUT')
"""
                        + scope(scope)
                        + " ORDER BY b.server_received_at,b.created_at,b.id",
                args(tenant, date, scope),
                (r, n) ->
                        new StaffPunch(
                                uuid(r, "id"),
                                new Punch(
                                        r.getString("event_type"),
                                        time(r, "client_occurred_at"),
                                        time(r, "server_received_at"))));
    }

    public List<StaffPlan> plans(UUID tenant, LocalDate date, Access scope) {
        return jdbc.query(
                """
SELECT p.id,a.id plan_id FROM sales_visit_plan a JOIN sales_profile p ON p.tenant_id=a.tenant_id AND p.id=a.sales_profile_id
WHERE p.tenant_id=:tenant AND a.planned_date=:date AND a.status<>'CANCELLED' AND (a.action_code IS NULL OR a.action_code='VISIT')
"""
                        + scope(scope),
                args(tenant, date, scope),
                (r, n) -> new StaffPlan(uuid(r, "id"), uuid(r, "plan_id")));
    }

    public List<StaffIssue> issues(UUID tenant, LocalDate date, Access scope) {
        return jdbc.query(
                "SELECT p.id,c.id issue,c.kind,c.title,c.status,c.visit_id FROM sales_profile p"
                        + DAY
                        + """
JOIN sales_fieldwork_case c ON c.tenant_id=d.tenant_id AND c.work_day_id=d.id
WHERE p.tenant_id=:tenant AND d.business_date=:date AND c.status IN ('OPEN','AWAITING_STATEMENT','EXPLAINED')
"""
                        + scope(scope)
                        + " ORDER BY c.detected_at,c.id",
                args(tenant, date, scope),
                (r, n) ->
                        new StaffIssue(
                                uuid(r, "id"),
                                new Issue(
                                        uuid(r, "issue"),
                                        uuid(r, "visit_id"),
                                        r.getString("kind"),
                                        r.getString("title"),
                                        r.getString("status"))));
    }

    @Override
    public Map<UUID, StoreVisitCounts> storeVisitCounts(UUID tenant, Access access, List<UUID> visitIds) {
        if (visitIds.isEmpty()) return Map.of();
        var params = new MapSqlParameterSource("tenant", SalesUuidCodec.encode(tenant))
                .addValue("visits", visitIds.stream().map(SalesUuidCodec::encode).toList())
                .addValue("codes", access.employeeCodes());
        return jdbc.query(
                """
                SELECT current_visit.id, COUNT(*) store_visits,
                    SUM(history.sales_profile_id=current_visit.sales_profile_id) salesperson_visits
                FROM sales_visit current_visit
                JOIN sales_work_day current_day
                    ON current_day.tenant_id=current_visit.tenant_id AND current_day.id=current_visit.work_day_id
                JOIN sales_visit history ON history.tenant_id=current_visit.tenant_id
                    AND (history.crm_store_id=current_visit.crm_store_id OR
                         (current_visit.crm_store_id IS NULL AND history.crm_store_id IS NULL
                          AND history.store_id=current_visit.store_id))
                    AND history.status<>'CANCELLED'
                    AND (history.checked_in_at<current_visit.checked_in_at
                         OR (history.checked_in_at=current_visit.checked_in_at AND history.id<=current_visit.id))
                JOIN sales_work_day history_day
                    ON history_day.tenant_id=history.tenant_id AND history_day.id=history.work_day_id
                    AND history_day.business_date<=current_day.business_date
                JOIN sales_profile p ON p.tenant_id=history.tenant_id AND p.id=history.sales_profile_id
                WHERE current_visit.tenant_id=:tenant AND current_visit.id IN (:visits)
                """ + scope(access) + " GROUP BY current_visit.id",
                params,
                rows -> {
                    var totals = new HashMap<UUID, StoreVisitCounts>();
                    while (rows.next()) {
                        totals.put(uuid(rows, "id"),
                                new StoreVisitCounts(rows.getLong("store_visits"), rows.getLong("salesperson_visits")));
                    }
                    return totals;
                });
    }

    public Optional<VisitContent> visitContent(UUID tenant, Access access, UUID visitId) {
        var params = args(tenant, null, access).addValue("visit", SalesUuidCodec.encode(visitId));
        return jdbc.query("""
                SELECT v.result_submitted_at, v.crm_fieldwork_json
                FROM sales_visit v JOIN sales_profile p ON p.tenant_id=v.tenant_id AND p.id=v.sales_profile_id
                WHERE v.tenant_id=:tenant AND v.id=:visit AND v.checked_in_at IS NOT NULL AND v.status<>'CANCELLED'
                """ + scope(access), params, (r, n) -> {
                    boolean submitted = r.getTimestamp("result_submitted_at") != null;
                    String snapshot = r.getString("crm_fieldwork_json");
                    return new VisitContent(submitted, submitted && snapshot != null
                            ? visitForm(json.readValue(snapshot, Accepted.class)) : null);
                }).stream().findFirst();
    }

    static VisitForm visitForm(Accepted accepted) {
        var d = accepted.draft();
        var labels = accepted.labels();
        java.util.function.BiFunction<String, String, String> label = (dict, code) ->
                code == null || code.isBlank() ? null : labels.getOrDefault(dict + ":" + code, code);
        var contacts = accepted.contacts().stream().map(c -> new VisitContact(c.name(), c.role())).toList();
        var businesses = d.businesses().stream().map(b -> new VisitBusiness(
                label.apply("BUSINESS_LINE", b.businessLineCode()),
                label.apply("CUSTOMER_INTENTION_LEVEL", b.intentionLevel()),
                label.apply("CUSTOMER_COOP_STATUS", b.cooperationStatus()))).toList();
        return new VisitForm(label.apply("STORE_BUSINESS_TYPE", d.businessTypeCode()),
                label.apply("CRM_STORE_BUSINESS_STATUS", d.businessStatus()), d.businessStatusReason(),
                label.apply("VISIT_CONTACT_OUTCOME", d.contactOutcome()), "CONTACTED".equals(d.contactOutcome()),
                contacts, d.noIntention(), businesses, label.apply("VISIT_NO_CONTACT_REASON", d.noContactReason()),
                d.noContactDetail(), label.apply("VISIT_NEXT_ACTION", d.nextAction()), d.nextContactDate());
    }

    public List<VisitFact> visits(
            UUID tenant, LocalDate from, LocalDate to, Access scope, UUID profile) {
        var args = args(tenant, from, scope).addValue("to", to);
        String person = profile == null ? "" : " AND p.id=:profile";
        if (profile != null) args.addValue("profile", SalesUuidCodec.encode(profile));
        return jdbc.query(
                """
WITH visits AS (
  SELECT v.*,d.business_date,p.hr_employee_code,p.sales_no,
    ROW_NUMBER() OVER(PARTITION BY v.work_day_id ORDER BY v.checked_in_at,v.id) seq
  FROM sales_visit v JOIN sales_work_day d ON d.tenant_id=v.tenant_id AND d.id=v.work_day_id
  JOIN sales_profile p ON p.tenant_id=v.tenant_id AND p.id=v.sales_profile_id
  WHERE p.tenant_id=:tenant AND (:date IS NULL OR d.business_date BETWEEN :date AND :to) AND v.checked_in_at IS NOT NULL AND v.status<>'CANCELLED'
"""
                        + scope(scope)
                        + person
                        + """
), clips AS (
  SELECT s.visit_id,c.verify_status,c.verified_duration_ms,
    COALESCE(IF(c.verify_status='VERIFIED',NULLIF(c.verified_duration_ms,0),NULL),
             NULLIF(c.client_duration_ms,0),
             IF(c.recorded_to>c.recorded_from,TIMESTAMPDIFF(MICROSECOND,c.recorded_from,c.recorded_to) DIV 1000,NULL)) playback_ms
  FROM visits v JOIN sales_recording_session s ON s.tenant_id=v.tenant_id AND s.visit_id=v.id
  JOIN sales_recording_clip c ON c.tenant_id=s.tenant_id AND c.recording_session_id=s.id AND c.upload_status='RECEIVED'
), recordings AS (
  SELECT visit_id,COUNT(*) files,
    SUM(verify_status='VERIFIED' AND verified_duration_ms IS NOT NULL) verified_files,
    SUM(IF(verify_status='VERIFIED',COALESCE(verified_duration_ms,0),0)) duration,
    IF(COUNT(playback_ms)=COUNT(*),SUM(playback_ms),NULL) playback_duration
  FROM clips GROUP BY visit_id
), storefront AS (
  SELECT e.visit_id,COUNT(*) total,SUM(e.evidence_status='TECHNICALLY_VERIFIED') verified
  FROM visits v JOIN sales_visit_evidence e ON e.tenant_id=v.tenant_id AND e.visit_id=v.id
  WHERE e.evidence_type='PHOTO' AND e.evidence_role='STOREFRONT' GROUP BY e.visit_id)
SELECT v.*,t.store_name,t.customer_name,
  IF(v.result_submitted_at IS NULL, NULL,
     JSON_UNQUOTE(JSON_EXTRACT(v.crm_fieldwork_json, CONCAT('$.labels.',JSON_QUOTE(CONCAT('VISIT_CONTACT_OUTCOME:',
       JSON_UNQUOTE(JSON_EXTRACT(v.crm_fieldwork_json,'$.draft.contactOutcome')))))))) communication_result,
  plan.objective,r.version_no,r.minimum_dwell_minutes,
  (SELECT MAX(cp.server_received_at) FROM sales_visit_checkpoint cp WHERE cp.tenant_id=v.tenant_id AND cp.visit_id=v.id) received_at,
  sf.verified photos,sf.total storefront_total,
  rec.files,rec.verified_files,rec.duration,rec.playback_duration
FROM visits v LEFT JOIN sales_visit_target_snapshot t ON t.tenant_id=v.tenant_id AND t.visit_id=v.id
LEFT JOIN sales_visit_plan plan ON plan.tenant_id=v.tenant_id AND plan.id=v.visit_plan_id
LEFT JOIN sales_visit_policy_version r ON r.tenant_id=v.tenant_id AND r.id=v.visit_policy_version_id
LEFT JOIN recordings rec ON rec.visit_id=v.id
LEFT JOIN storefront sf ON sf.visit_id=v.id
ORDER BY v.business_date DESC,v.checked_in_at,v.id
""",
                args,
                (r, n) -> {
                    UUID store = uuid(r, "store_id");
                    Long crmStore = (Long) r.getObject("crm_store_id");
                    String status = r.getString("status");
                    Instant departure = time(r, "checked_out_at");
                    String assessment =
                            departure == null
                                    ? "IN_PROGRESS"
                                    : switch (Objects.toString(
                                            r.getString("final_reason_code"), "")) {
                                        case "EFFECTIVE" -> "VALID";
                                        case "INEFFECTIVE", "INVALID" -> "INVALID";
                                        default -> "PENDING";
                                    };
                    return new VisitFact(
                            new Visit(
                                    uuid(r, "id"),
                                    uuid(r, "sales_profile_id"),
                                    r.getObject("business_date", LocalDate.class),
                                    r.getString("hr_employee_code"),
                                    r.getString("sales_no"),
                                    r.getString("store_name"),
                                    r.getString("customer_name"),
                                    status,
                                    time(r, "checked_in_at"),
                                    departure,
                                    r.getString("result_note"),
                                    r.getString("contact_outcome"),
                                    r.getString("intention_level"),
                                    uuid(r, "visit_plan_id"),
                                    r.getInt("seq"),
                                    assessment,
                                    r.getString("final_reason_code"),
                                    r.getObject("version_no") == null
                                            ? null
                                            : "v" + r.getInt("version_no"),
                                    r.getObject("minimum_dwell_minutes", Integer.class),
                                    r.getInt("photos"),
                                    r.getInt("files"),
                                    r.getInt("verified_files"),
                                    r.getLong("duration"),
                                    r.getString("objective"),
                                    null,
                                    null,
                                    time(r, "received_at"),
                                    r.getInt("storefront_total") > 0,
                                    r.getObject("playback_duration", Long.class), r.getString("communication_result")),
                            store == null ? null : "S" + store,
                            (Long) r.getObject("crm_visit_id"), crmStore);
                });
    }
}
