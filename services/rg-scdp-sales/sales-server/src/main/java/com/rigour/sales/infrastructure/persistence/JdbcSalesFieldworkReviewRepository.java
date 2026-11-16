package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.application.port.out.SalesFieldworkReviewRepository;
import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Case;
import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Event;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalesFieldworkReviewRepository implements SalesFieldworkReviewRepository {
    private final JdbcTemplate jdbc;
    public JdbcSalesFieldworkReviewRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override
    public void detect(UUID tenant, UUID profile, UUID day, UUID visit, String key, String kind, String title, String evidenceJson, Instant now) {
        // Stable evidence identity: repeated calculations never erase the human decision or original evidence.
        jdbc.update("""
                INSERT INTO sales_fieldwork_case (id,tenant_id,sales_profile_id,work_day_id,visit_id,evidence_key,kind,title,evidence_json,detected_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE evidence_key=evidence_key
                """,bin(UUID.randomUUID()),bin(tenant),bin(profile),bin(day),bin(visit),key,kind,title,evidenceJson,ts(now),ts(now));
    }

    @Override public List<Case> byDay(UUID tenant, UUID day) { return list(tenant,"work_day_id",day); }
    @Override public List<Case> byVisit(UUID tenant, UUID visit) { return list(tenant,"visit_id",visit); }
    private List<Case> list(UUID tenant, String field, UUID id) {
        var rows=jdbc.query("SELECT * FROM sales_fieldwork_case WHERE tenant_id=? AND "+field+"=? ORDER BY detected_at,id",
                (r,n)->row(r,List.of()),bin(tenant),bin(id));
        if(rows.isEmpty()) return rows;
        // One batched history lookup; no query per case.
        var events=jdbc.query("SELECT e.* FROM sales_fieldwork_case_event e JOIN sales_fieldwork_case c ON c.tenant_id=e.tenant_id AND c.id=e.case_id WHERE c.tenant_id=? AND c."+field+"=? ORDER BY e.created_at,e.id",
                (r,n)->new History(uuid(r.getBytes("case_id")),event(r)),bin(tenant),bin(id));
        return rows.stream().map(c->withEvents(c,events.stream().filter(e->e.caseId().equals(c.id())).map(History::event).toList())).toList();
    }
    private record History(UUID caseId,Event event) {}

    @Override public Optional<Case> find(UUID tenant, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM sales_fieldwork_case WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (r,n)->row(r,List.of()),bin(tenant),bin(id)).stream().findFirst();
    }
    @Override public Optional<Case> findEvidence(UUID tenant,String evidenceKey) {
        return jdbc.query("SELECT * FROM sales_fieldwork_case WHERE tenant_id=? AND evidence_key=?",(r,n)->row(r,List.of()),bin(tenant),evidenceKey).stream().findFirst();
    }
    @Override public Optional<Command> command(UUID tenant, UUID id, UUID user, String requestId) {
        return jdbc.query("SELECT event_type,decision,note FROM sales_fieldwork_case_event WHERE tenant_id=? AND case_id=? AND actor_user_id=? AND request_id=?",
                (r,n)->new Command(r.getString(1),r.getString(2),r.getString(3)),bin(tenant),bin(id),bin(user),requestId).stream().findFirst();
    }
    @Override public void append(UUID tenant, UUID id, UUID user, String requestId, String type, String decision, String note, String status, Instant now) {
        jdbc.update("INSERT INTO sales_fieldwork_case_event(id,tenant_id,case_id,actor_user_id,request_id,event_type,decision,note,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bin(UUID.randomUUID()),bin(tenant),bin(id),bin(user),requestId,type,decision,note,ts(now));
        jdbc.update("UPDATE sales_fieldwork_case SET status=?,version=version+1,updated_at=? WHERE tenant_id=? AND id=?",status,ts(now),bin(tenant),bin(id));
    }
    private static Case row(ResultSet r,List<Event> events) throws SQLException {
        return new Case(uuid(r.getBytes("id")),uuid(r.getBytes("sales_profile_id")),uuid(r.getBytes("work_day_id")),uuid(r.getBytes("visit_id")),r.getString("kind"),r.getString("title"),r.getString("evidence_json"),r.getString("status"),r.getLong("version"),r.getTimestamp("detected_at").toInstant(),r.getTimestamp("updated_at").toInstant(),events);
    }
    private static Case withEvents(Case c,List<Event> events) { return new Case(c.id(),c.salesProfileId(),c.workDayId(),c.visitId(),c.kind(),c.title(),c.evidenceJson(),c.status(),c.version(),c.detectedAt(),c.updatedAt(),events); }
    private static Event event(ResultSet r) throws SQLException { return new Event(uuid(r.getBytes("id")),r.getString("event_type"),r.getString("decision"),r.getString("note"),"STATEMENT".equals(r.getString("event_type")),r.getTimestamp("created_at").toInstant()); }
    private static byte[] bin(UUID id) { return id==null?null:SalesUuidCodec.encode(id); }
    private static UUID uuid(byte[] value) { return value==null?null:SalesUuidCodec.decode(value); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
