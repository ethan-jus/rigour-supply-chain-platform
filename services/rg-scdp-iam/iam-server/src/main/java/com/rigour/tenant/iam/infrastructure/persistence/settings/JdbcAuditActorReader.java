package com.rigour.tenant.iam.infrastructure.persistence.settings;
import com.rigour.tenant.iam.application.port.out.AuditActorReader;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class JdbcAuditActorReader implements AuditActorReader {
    private final JdbcTemplate jdbc;
    public JdbcAuditActorReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<ActorName> actors(UUID tenant,List<String> refs) {
        if(refs.isEmpty()) return List.of();
        String marks=String.join(",",Collections.nCopies(refs.size(),"?"));
        var args=new ArrayList<Object>(); args.add(JdbcAppSettingsStore.bin(tenant));args.addAll(refs);args.addAll(refs);
        return jdbc.query("SELECT DISTINCT u.id,u.display_name,b.employee_code FROM iam_user u LEFT JOIN iam_app_employee_binding b ON b.tenant_id=u.tenant_id AND b.user_id=u.id WHERE u.tenant_id=? AND (LOWER(BIN_TO_UUID(u.id)) IN ("+marks+") OR b.employee_code IN ("+marks+"))",
                (r,n)->new ActorName(UuidBinaryCodec.decode(r.getBytes("id")).toString(),r.getString("employee_code"),r.getString("display_name")),args.toArray());
    }
}
