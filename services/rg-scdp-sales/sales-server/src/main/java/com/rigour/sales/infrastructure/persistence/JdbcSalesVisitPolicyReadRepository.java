package com.rigour.sales.infrastructure.persistence;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.*;
import com.rigour.sales.application.port.out.SalesVisitPolicyReadRepository;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import com.rigour.shared.cache.QueryCache;
import org.springframework.beans.factory.annotation.Autowired;
@Repository
public class JdbcSalesVisitPolicyReadRepository implements SalesVisitPolicyReadRepository {
    @Autowired(required=false) private QueryCache queryCache=QueryCache.disabled();
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public JdbcSalesVisitPolicyReadRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public List<Version> versions(UUID tenant) {
        return jdbc.query("""
            SELECT v.*,p.policy_code,p.policy_name,s.scope_type,s.scope_id FROM sales_visit_policy_version v
            JOIN sales_visit_policy p ON p.tenant_id=v.tenant_id AND p.id=v.policy_id
            LEFT JOIN sales_policy_scope s ON s.tenant_id=v.tenant_id AND s.policy_version_id=v.id AND s.policy_type='VISIT' AND s.status='ACTIVE'
            WHERE v.tenant_id=? AND v.source_system='SALES' ORDER BY v.created_at DESC,v.version_no DESC LIMIT 30
            """,(r,n)->new Version(uuid(r,"id").toString(),r.getInt("version_no"),r.getString("policy_code"),r.getString("policy_name"),r.getString("publish_status"),r.getString("scope_type"),uuid(r,"scope_id"),policy(r),r.getTimestamp("created_at").toInstant(),uuid(r,"created_by").toString()),SalesUuidCodec.encode(tenant));
    }
    public Snapshot snapshot(UUID tenant,UUID version) {
        return queryCache.read(tenant.toString(),"visit-policy-snapshot",version,86400,new TypeReference<List<Snapshot>>() {},
            () -> jdbc.query("SELECT * FROM sales_visit_policy_version WHERE tenant_id=? AND id=?",(r,n)->policy(r),SalesUuidCodec.encode(tenant),SalesUuidCodec.encode(version)))
            .stream().findFirst().orElse(null);
    }
    private Snapshot policy(ResultSet r) throws SQLException {
        String raw=r.getString("capture_rules_json");
        return new Snapshot(uuid(r,"id").toString(),r.getInt("check_in_radius_meters"),r.getInt("minimum_dwell_minutes"),r.getInt("required_photo_count"),r.getBoolean("recording_enabled"),r.getInt("minimum_recording_seconds"),r.getInt("maximum_clip_gap_seconds"),r.getBoolean("ai_asr_enabled"),r.getBoolean("ai_relevance_enabled"),r.getBoolean("ai_duplicate_enabled"),r.getBigDecimal("ai_auto_confirm_threshold"),raw==null ? CaptureRules.legacy() : json.readValue(raw,CaptureRules.class));
    }
    private static UUID uuid(ResultSet r,String column) throws SQLException { byte[] bytes=r.getBytes(column);return bytes==null ? null : SalesUuidCodec.decode(bytes); }
}
