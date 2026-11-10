package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.port.out.HuolalaStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** 只写Integration自己的Schema，订单及员工业务主档由领域接口持有。 */
@Repository
public class JdbcHuolalaStore implements HuolalaStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcHuolalaStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Connection> connection(UUID tenant) {
        return jdbc
                .query(
                        "SELECT * FROM integration_huolala_connector WHERE tenant_id=? ORDER BY"
                                + " created_at LIMIT 1",
                        (r, n) -> connection(r),
                        bytes(tenant))
                .stream()
                .findFirst();
    }

    private Connection connection(ResultSet r) throws SQLException {
        var c = json.readValue(r.getString("config_json"), Connection.class);
        return new Connection(
                uuid(r.getBytes("id")).toString(),
                r.getString("enterprise_id"),
                c.enterpriseName(),
                c.secretRef(),
                c.sandbox(),
                c.redirectUri(),
                r.getBoolean("enabled"),
                c.intervalMinutes(),
                c.startDate(),
                r.getString("auth_status"),
                instant(r, "token_expires_at"),
                false,
                r.getLong("revision"),
                instant(r, "last_success_at"),
                r.getString("last_error"));
    }

    @Override
    @Transactional
    public Connection save(UUID tenant, UUID actor, SaveConnection command) {
        var old = connection(tenant);
        UUID id = old.map(c -> UUID.fromString(c.id())).orElseGet(UUID::randomUUID);
        var c =
                new Connection(
                        id.toString(),
                        command.enterpriseId(),
                        command.enterpriseName(),
                        command.secretRef(),
                        command.sandbox(),
                        command.redirectUri(),
                        command.enabled(),
                        command.intervalMinutes(),
                        command.startDate(),
                        "UNAUTHORIZED",
                        null,
                        false,
                        1,
                        null,
                        null);
        if (old.isEmpty()) {
            if (command.revision() != 0) throw conflict();
            jdbc.update(
                    "INSERT INTO"
                        + " integration_huolala_connector(id,tenant_id,enterprise_id,config_json,enabled,next_sync_at,created_by)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    bytes(id),
                    bytes(tenant),
                    command.enterpriseId(),
                    json.writeValueAsString(c),
                    command.enabled(),
                    utc(Instant.now()),
                    bytes(actor));
        } else {
            var previous = old.get();
            if (!previous.enterpriseId().equals(command.enterpriseId())
                    || previous.sandbox() != command.sandbox())
                throw new IllegalArgumentException("已有连接的企业和环境不能更换，避免混用历史订单");
            boolean authChanged =
                    !Objects.equals(previous.secretRef(), command.secretRef())
                            || !Objects.equals(previous.redirectUri(), command.redirectUri());
            if (jdbc.update(
                            "UPDATE integration_huolala_connector SET"
                                + " config_json=?,enabled=?,next_sync_at=?,auth_status=CASE WHEN ?"
                                + " THEN 'UNAUTHORIZED' ELSE auth_status"
                                + " END,revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE"
                                + " tenant_id=? AND id=? AND revision=? AND lease_id IS NULL AND"
                                + " (auth_state_hash IS NULL OR auth_expires_at<UTC_TIMESTAMP(6))",
                            json.writeValueAsString(c),
                            command.enabled(),
                            utc(Instant.now()),
                            authChanged,
                            bytes(tenant),
                            bytes(id),
                            command.revision())
                    != 1) throw conflict();
        }
        UUID task = task(id);
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " integration_sync_task(id,tenant_id,connector_id,task_code,object_type,task_status,created_at,updated_at,created_by)"
                    + " VALUES(?,?,?,'HLL_ORDER_SYNC','HLL_ORDER','IDLE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)",
                bytes(task),
                bytes(tenant),
                bytes(id),
                bytes(actor));
        return connection(tenant).orElseThrow();
    }

    @Override
    public void beginAuth(UUID tenant, UUID actor, String hash, Instant expiry) {
        if (jdbc.update(
                        "UPDATE integration_huolala_connector SET"
                                + " auth_state_hash=?,auth_actor_id=?,auth_expires_at=? WHERE"
                                + " tenant_id=? AND lease_id IS NULL",
                        hash,
                        bytes(actor),
                        utc(expiry),
                        bytes(tenant))
                != 1) throw conflict();
    }

    @Override
    public boolean consumeAuth(UUID tenant, UUID actor, String hash, Instant now) {
        return jdbc.update(
                        "UPDATE integration_huolala_connector SET"
                            + " auth_state_hash='CONSUMED',auth_expires_at=? WHERE tenant_id=? AND"
                            + " auth_actor_id=? AND auth_state_hash=? AND auth_expires_at>? AND"
                            + " lease_id IS NULL",
                        utc(now.plusSeconds(120)),
                        bytes(tenant),
                        bytes(actor),
                        hash,
                        utc(now))
                == 1;
    }

    @Override
    public void authorized(UUID tenant, Instant expiry) {
        var c = connection(tenant).orElseThrow();
        jdbc.update(
                "UPDATE integration_huolala_connector SET"
                    + " auth_status='AUTHORIZED',auth_state_hash=NULL,auth_expires_at=NULL,token_secret_ref=?,token_expires_at=?,last_error=NULL,revision=revision+1"
                    + " WHERE tenant_id=?",
                tenant + "/" + c.id(),
                utc(expiry),
                bytes(tenant));
    }

    @Override
    public void expired(UUID tenant) {
        jdbc.update(
                "UPDATE integration_huolala_connector SET auth_status='EXPIRED' WHERE tenant_id=?",
                bytes(tenant));
    }

    @Override
    @Transactional
    public boolean claim(UUID tenant, String run, Instant now) {
        jdbc.query(
                "SELECT id FROM integration_huolala_connector WHERE tenant_id=? FOR UPDATE",
                (r, n) -> r.getBytes(1),
                bytes(tenant));
        var abandoned =
                jdbc.query(
                        "SELECT lease_id FROM integration_huolala_connector WHERE tenant_id=? AND"
                                + " lease_id IS NOT NULL AND lease_expires_at<?",
                        (r, n) -> r.getString(1),
                        bytes(tenant),
                        utc(now));
        for (var old : abandoned)
            jdbc.update(
                    "UPDATE integration_sync_run SET"
                        + " status='FAILED',finished_at=?,updated_at=?,error_message='任务心跳中断，请重新补同步；重复来源会自动去重'"
                        + " WHERE tenant_id=? AND id=? AND status='RUNNING'",
                    utc(now),
                    utc(now),
                    bytes(tenant),
                    bytes(UUID.fromString(old)));
        return jdbc.update(
                        "UPDATE integration_huolala_connector SET"
                            + " lease_id=?,lease_expires_at=?,auth_state_hash=NULL WHERE"
                            + " tenant_id=? AND (auth_state_hash IS NULL OR auth_expires_at<?) AND"
                            + " (lease_id IS NULL OR lease_expires_at<?)",
                        run,
                        utc(now.plusSeconds(300)),
                        bytes(tenant),
                        utc(now),
                        utc(now))
                == 1;
    }

    @Override
    public void heartbeat(UUID tenant, String run, Instant now) {
        if (jdbc.update(
                        "UPDATE integration_huolala_connector SET lease_expires_at=? WHERE"
                                + " tenant_id=? AND lease_id=? AND lease_expires_at>?",
                        utc(now.plusSeconds(300)),
                        bytes(tenant),
                        run,
                        utc(now))
                != 1) throw new IllegalStateException("同步任务租约失效，已停止处理");
    }

    @Override
    public void finish(UUID tenant, String run, String error, Instant now) {
        var c = connection(tenant).orElseThrow();
        jdbc.update(
                "UPDATE integration_huolala_connector SET"
                        + " lease_id=NULL,lease_expires_at=NULL,next_sync_at=?,last_success_at=CASE"
                        + " WHEN ? IS NULL THEN ? ELSE last_success_at END,last_error=? WHERE"
                        + " tenant_id=? AND lease_id=?",
                utc(now.plusSeconds(c.intervalMinutes() * 60L)),
                error,
                utc(now),
                error,
                bytes(tenant),
                run);
    }

    @Override
    public List<UUID> due(Instant now) {
        return jdbc.query(
                "SELECT tenant_id FROM integration_huolala_connector WHERE enabled=1 AND"
                        + " auth_status='AUTHORIZED' AND next_sync_at<=? AND (lease_id IS NULL OR"
                        + " lease_expires_at<?)",
                (r, n) -> uuid(r.getBytes(1)),
                utc(now),
                utc(now));
    }

    @Override
    public UUID startRun(UUID tenant, UUID actor, String scope, String trigger, Instant now) {
        var c = connection(tenant).orElseThrow();
        UUID run = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " integration_sync_run(id,tenant_id,task_id,trigger_type,status,cursor_before,started_at,created_at,updated_at,created_by)"
                    + " VALUES(?,?,?,?,'RUNNING',?,?,?,?,?)",
                bytes(run),
                bytes(tenant),
                bytes(task(UUID.fromString(c.id()))),
                trigger,
                scope,
                utc(now),
                utc(now),
                utc(now),
                bytes(actor));
        return run;
    }

    @Override
    public void finishRun(
            UUID tenant,
            UUID run,
            long fetched,
            long accepted,
            long unchanged,
            long rejected,
            String error,
            Instant now) {
        jdbc.update(
                "UPDATE integration_sync_run SET"
                    + " status=?,fetched_count=?,accepted_count=?,duplicate_count=?,rejected_count=?,finished_at=?,error_message=?,updated_at=?"
                    + " WHERE tenant_id=? AND id=?",
                error != null ? "FAILED" : rejected > 0 ? "PARTIAL" : "SUCCEEDED",
                fetched,
                accepted,
                unchanged,
                rejected,
                utc(now),
                error,
                utc(now),
                bytes(tenant),
                bytes(run));
    }

    @Override
    public void progress(
            UUID tenant,
            UUID run,
            long fetched,
            long accepted,
            long unchanged,
            long rejected,
            LocalDate from,
            LocalDate to,
            int page) {
        jdbc.update(
                "UPDATE integration_sync_run SET"
                    + " fetched_count=?,accepted_count=?,duplicate_count=?,rejected_count=?,window_from=?,window_to=?,cursor_after=?,updated_at=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND id=? AND status='RUNNING'",
                fetched,
                accepted,
                unchanged,
                rejected,
                businessDayUtc(from),
                businessDayUtc(to.plusDays(1)),
                Integer.toString(page),
                bytes(tenant),
                bytes(run));
    }

    @Override
    public List<Run> runs(UUID tenant) {
        var c = connection(tenant);
        if (c.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT * FROM integration_sync_run WHERE tenant_id=? AND task_id=? ORDER BY"
                        + " created_at DESC LIMIT 50",
                (r, n) ->
                        new Run(
                                uuid(r.getBytes("id")).toString(),
                                r.getString("cursor_before"),
                                r.getString("status"),
                                r.getLong("fetched_count"),
                                r.getLong("accepted_count"),
                                r.getLong("duplicate_count"),
                                r.getLong("rejected_count"),
                                instant(r, "started_at"),
                                instant(r, "finished_at"),
                                r.getString("error_message")),
                bytes(tenant),
                bytes(task(UUID.fromString(c.get().id()))));
    }

    @Override
    public List<SyncIssue> issues(UUID tenant, UUID run) {
        var c = connection(tenant);
        if (c.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT d.* FROM integration_dead_letter d JOIN integration_sync_run r ON"
                        + " r.id=d.run_id AND r.tenant_id=d.tenant_id WHERE d.tenant_id=? AND"
                        + " d.run_id=? AND r.task_id=? ORDER BY d.created_at DESC LIMIT 500",
                (r, n) ->
                        new SyncIssue(
                                r.getString("source_object_type"),
                                r.getString("source_id"),
                                r.getString("last_error_message"),
                                instant(r, "created_at")),
                bytes(tenant),
                bytes(run),
                bytes(task(UUID.fromString(c.get().id()))));
    }

    @Override
    public boolean historyDue(UUID tenant, LocalDate today) {
        var c = connection(tenant).orElseThrow();
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM integration_sync_run WHERE tenant_id=? AND task_id=?"
                                + " AND trigger_type='SCHEDULED' AND status='SUCCEEDED' AND"
                                + " cursor_before='ALL' AND started_at>=? AND window_from<=?",
                        Long.class,
                        bytes(tenant),
                        bytes(task(UUID.fromString(c.id()))),
                        today.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                .withZoneSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime(),
                        businessDayUtc(
                                today.minusYears(1).plusDays(1).isAfter(c.startDate())
                                        ? today.minusYears(1).plusDays(1)
                                        : c.startDate()))
                == 0;
    }

    @Override
    public String raw(
            UUID tenant, UUID connector, UUID run, String type, String sourceId, JsonNode payload) {
        String encoded = json.writeValueAsString(payload), hash = sha(encoded);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " integration_raw_landing(id,tenant_id,connector_id,run_id,source_system,source_object_type,source_id,payload_json,payload_checksum,received_at,landing_status)"
                    + " VALUES(?,?,?,?,'HLL',?,?,?,?,UTC_TIMESTAMP(6),'RECEIVED')",
                bytes(id),
                bytes(tenant),
                bytes(connector),
                bytes(run),
                type,
                sourceId,
                encoded,
                hash);
        jdbc.update(
                "UPDATE integration_raw_landing SET last_attempt_at=UTC_TIMESTAMP(6) WHERE"
                        + " tenant_id=? AND connector_id=? AND source_system='HLL' AND"
                        + " source_object_type=? AND source_id=? AND payload_checksum=?",
                bytes(tenant),
                bytes(connector),
                type,
                sourceId,
                hash);
        return jdbc.query(
                        "SELECT id FROM integration_raw_landing WHERE tenant_id=? AND"
                            + " connector_id=? AND source_system='HLL' AND source_object_type=? AND"
                            + " source_id=? AND payload_checksum=?",
                        (r, n) -> uuid(r.getBytes(1)).toString(),
                        bytes(tenant),
                        bytes(connector),
                        type,
                        sourceId,
                        hash)
                .getFirst();
    }

    @Override
    @Transactional
    public void delivery(UUID tenant, UUID run, String rawId, String sourceId, String error) {
        jdbc.update(
                "UPDATE integration_raw_landing SET"
                    + " landing_status=?,processed_at=UTC_TIMESTAMP(6),error_message=?,attempts=attempts+1,last_attempt_at=UTC_TIMESTAMP(6)"
                    + " WHERE tenant_id=? AND id=?",
                error == null ? "PROCESSED" : "FAILED",
                error,
                bytes(tenant),
                bytes(UUID.fromString(rawId)));
        String type =
                jdbc.queryForObject(
                        "SELECT source_object_type FROM integration_raw_landing WHERE tenant_id=?"
                                + " AND id=?",
                        String.class,
                        bytes(tenant),
                        bytes(UUID.fromString(rawId)));
        String objectType = type.startsWith("EMPLOYEE") ? "EMPLOYEE" : "ORDER";
        if (error == null)
            jdbc.update(
                    "UPDATE integration_dead_letter SET"
                        + " status='RESOLVED',resolved_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)"
                        + " WHERE tenant_id=? AND source_system='HLL' AND source_object_type=? AND"
                        + " source_id=? AND status='OPEN'",
                    bytes(tenant),
                    objectType,
                    sourceId);
        else
            jdbc.update(
                    "INSERT INTO"
                        + " integration_dead_letter(id,tenant_id,run_id,raw_landing_id,source_system,source_object_type,source_id,status,last_error_message,created_at,updated_at)"
                        + " VALUES(?,?,?,?,'HLL',?,?,'OPEN',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    bytes(UUID.randomUUID()),
                    bytes(tenant),
                    bytes(run),
                    bytes(UUID.fromString(rawId)),
                    objectType,
                    sourceId,
                    error);
    }

    @Override
    public List<Employee> employees(UUID tenant) {
        var c = connection(tenant);
        if (c.isEmpty()) return List.of();
        var latest =
                jdbc.query(
                        "SELECT payload_json FROM integration_raw_landing r WHERE tenant_id=? AND"
                                + " connector_id=? AND source_system='HLL' AND"
                                + " source_object_type='EMPLOYEE' ORDER BY"
                                + " COALESCE(last_attempt_at,received_at) DESC,id DESC LIMIT 10000",
                        (r, n) -> json.readTree(r.getString(1)),
                        bytes(tenant),
                        bytes(UUID.fromString(c.get().id())));
        Map<String, Employee> rows = new LinkedHashMap<>();
        Map<String, Map<String, Object>> mappings = new HashMap<>();
        jdbc.query(
                "SELECT source_object_id,internal_object_no,remark,version,mapping_status FROM"
                    + " integration_external_object_mapping WHERE tenant_id=? AND connector_id=?"
                    + " AND source_system='HLL' AND source_object_type='HLL_STAFF' AND deleted_at"
                    + " IS NULL",
                r -> {
                    mappings.put(
                            r.getString(1),
                            Map.of(
                                    "code",
                                    Objects.toString(r.getString(2), ""),
                                    "remark",
                                    Objects.toString(r.getString(3), ""),
                                    "revision",
                                    r.getLong(4),
                                    "status",
                                    r.getString(5)));
                },
                bytes(tenant),
                bytes(UUID.fromString(c.get().id())));
        for (var node : latest) {
            String staff = node.path("staff_id").asText("");
            if (staff.isBlank() || rows.containsKey(staff)) continue;
            Map<String, ?> mapping =
                    mappings.getOrDefault(c.get().enterpriseId() + ":" + staff, Map.of());
            JsonNode snapshot =
                    mapping.get("remark") == null || mapping.get("remark").toString().isBlank()
                            ? json.createObjectNode()
                            : json.readTree(mapping.get("remark").toString());
            boolean active = "ACTIVE".equals(mapping.get("status"));
            rows.put(
                    staff,
                    new Employee(
                            staff,
                            node.path("user_id").asText(""),
                            node.path("staff_name").asText(""),
                            node.path("phone_no").asText(""),
                            node.path("staff_no").asText(""),
                            node.path("depart_id").asText(""),
                            active ? mapping.get("code").toString() : null,
                            active ? snapshot.path("employeeName").asText(null) : null,
                            active ? snapshot.path("departmentName").asText(null) : null,
                            active ? "LINKED" : "UNMATCHED",
                            mapping.containsKey("revision")
                                    ? ((Number) mapping.get("revision")).longValue()
                                    : 0));
        }
        return List.copyOf(rows.values());
    }

    @Override
    public Optional<Employee> employee(UUID tenant, String staffId) {
        return employees(tenant).stream().filter(e -> e.staffId().equals(staffId)).findFirst();
    }

    @Override
    public void observeEmployee(UUID tenant, UUID connector, UUID run, JsonNode employee) {
        raw(tenant, connector, run, "EMPLOYEE", employee.path("staff_id").asText(), employee);
    }

    @Override
    @Transactional
    public void bind(
            UUID tenant,
            UUID actor,
            Employee external,
            EmployeeCandidate internal,
            long revision,
            String reason) {
        var c = connection(tenant).orElseThrow();
        UUID connector = UUID.fromString(c.id());
        jdbc.query(
                "SELECT id FROM integration_huolala_connector WHERE tenant_id=? FOR UPDATE",
                (r, n) -> r.getBytes(1),
                bytes(tenant));
        var current = employee(tenant, external.staffId()).orElseThrow();
        if (current.revision() != revision) throw conflict();
        String remark =
                json.writeValueAsString(
                        Map.of(
                                "employeeName",
                                internal == null ? "" : internal.employeeName(),
                                "departmentName",
                                internal == null
                                        ? ""
                                        : Objects.toString(internal.departmentName(), "")));
        for (String type : List.of("HLL_STAFF", "HLL_USER")) {
            String source =
                    c.enterpriseId()
                            + ":"
                            + (type.equals("HLL_STAFF") ? external.staffId() : external.userId());
            if (source.endsWith(":")) continue;
            jdbc.update(
                    "INSERT INTO"
                        + " integration_external_object_mapping(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,internal_domain,internal_object_type,internal_object_id,internal_object_no,mapping_status,remark,version,created_at,created_by,updated_at,updated_by)"
                        + " VALUES(?,?,?,'HLL',?,?,'HR','EMPLOYEE',?,?,?,?,1,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + " internal_object_id=VALUES(internal_object_id),internal_object_no=VALUES(internal_object_no),mapping_status=VALUES(mapping_status),remark=VALUES(remark),version=version+1,updated_at=UTC_TIMESTAMP(6),updated_by=VALUES(updated_by)",
                    bytes(UUID.randomUUID()),
                    bytes(tenant),
                    bytes(connector),
                    type,
                    source,
                    internal == null ? null : internal.id(),
                    internal == null ? null : internal.employeeCode(),
                    internal == null ? "REMOVED" : "ACTIVE",
                    remark,
                    bytes(actor),
                    bytes(actor));
        }
        jdbc.update(
                "UPDATE integration_manual_resolution SET"
                        + " status='SUPERSEDED',updated_at=UTC_TIMESTAMP(6),updated_by=? WHERE"
                        + " tenant_id=? AND connector_id=? AND source_system='HLL' AND"
                        + " resolution_type='EMPLOYEE_BINDING' AND source_id=? AND status='ACTIVE'",
                bytes(actor),
                bytes(tenant),
                bytes(connector),
                external.staffId());
        jdbc.update(
                "INSERT INTO"
                    + " integration_manual_resolution(id,tenant_id,connector_id,source_system,resolution_type,source_object_type,source_id,selected_source_object_type,selected_source_id,selected_internal_object_type,selected_internal_object_id,reason,status,created_at,created_by,updated_at,updated_by)"
                    + " VALUES(?,?,?,'HLL','EMPLOYEE_BINDING','EMPLOYEE',?,'HR_EMPLOYEE',?,'EMPLOYEE',?,?,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6),?)",
                bytes(UUID.randomUUID()),
                bytes(tenant),
                bytes(connector),
                external.staffId(),
                internal == null ? "UNLINKED" : internal.employeeCode(),
                internal == null ? null : internal.id(),
                reason,
                internal == null ? "CANCELLED" : "ACTIVE",
                bytes(actor),
                bytes(actor));
    }

    @Override
    public String mappedEmployee(UUID tenant, String userId) {
        var c = connection(tenant);
        if (c.isEmpty() || userId == null || userId.isBlank()) return null;
        return jdbc
                .query(
                        "SELECT internal_object_no FROM integration_external_object_mapping WHERE"
                                + " tenant_id=? AND connector_id=? AND source_system='HLL' AND"
                                + " source_object_type='HLL_USER' AND source_object_id=? AND"
                                + " mapping_status='ACTIVE'",
                        (r, n) -> r.getString(1),
                        bytes(tenant),
                        bytes(UUID.fromString(c.get().id())),
                        c.get().enterpriseId() + ":" + userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("摘要不可用");
        }
    }

    private static UUID task(UUID connector) {
        return UUID.nameUUIDFromBytes(("HLL:" + connector).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(UUID u) {
        return ByteBuffer.allocate(16)
                .putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] b) {
        var v = ByteBuffer.wrap(b);
        return new UUID(v.getLong(), v.getLong());
    }

    private static LocalDateTime businessDayUtc(LocalDate day) {
        return utc(day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet r, String name) throws SQLException {
        var t = r.getObject(name, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("配置、关联或任务状态已变化，请刷新后重试");
    }
}
