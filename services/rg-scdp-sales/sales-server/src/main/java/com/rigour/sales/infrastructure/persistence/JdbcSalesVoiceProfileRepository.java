package com.rigour.sales.infrastructure.persistence;

import com.rigour.sales.api.v1.SalesVoiceProfileApi.Profile;
import com.rigour.sales.application.port.out.*;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSalesVoiceProfileRepository implements SalesVoiceProfileRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public JdbcSalesVoiceProfileRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public Profile read(UUID tenant,UUID profile,String name){
        return jdbc.query("SELECT embedding_json IS NOT NULL registered,revision,updated_at FROM sales_voice_profile WHERE tenant_id=? AND sales_profile_id=?",
                (r,n)->new Profile(r.getBoolean(1),name,r.getLong(2),r.getTimestamp(3).toInstant()),bin(tenant),bin(profile)).stream()
                .findFirst().orElse(new Profile(false,name,0,null));
    }
    @Override @Transactional public Profile save(UUID tenant,UUID profile,String name,long revision,SalesVoiceEnrollmentClient.Enrollment voice,Instant now){
        jdbc.update("INSERT INTO sales_voice_profile(tenant_id,sales_profile_id,employee_name,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE sales_profile_id=sales_profile_id",bin(tenant),bin(profile),name,Timestamp.from(now));
        if(jdbc.update("UPDATE sales_voice_profile SET employee_name=?,embedding_json=?,model=?,revision=revision+1,consented_at=?,updated_at=? WHERE tenant_id=? AND sales_profile_id=? AND revision=?",
                name,json.writeValueAsString(voice.embedding()),voice.model(),Timestamp.from(now),Timestamp.from(now),bin(tenant),bin(profile),revision)!=1)
            throw new StateConflictException("声音登记已变化，请刷新后重试");
        return read(tenant,profile,name);
    }
    @Override @Transactional public Profile delete(UUID tenant,UUID profile,String name,long revision,Instant now){
        if(jdbc.update("UPDATE sales_voice_profile SET embedding_json=NULL,model=NULL,consented_at=NULL,revision=revision+1,updated_at=? WHERE tenant_id=? AND sales_profile_id=? AND revision=?",
                Timestamp.from(now),bin(tenant),bin(profile),revision)!=1)throw new StateConflictException("声音登记已变化，请刷新后重试");
        return read(tenant,profile,name);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000)
    public void identifyVisits() {
        var candidates=jdbc.queryForList("""
                SELECT c.tenant_id,c.visit_id,c.version,c.profiles_json,
                    (SELECT COALESCE(SUM(p.revision),0) FROM sales_voice_profile p WHERE p.tenant_id=c.tenant_id) registry_version
                FROM sales_visit_speaker_context c JOIN sales_visit v ON v.tenant_id=c.tenant_id AND v.id=c.visit_id
                WHERE v.status='CHECKED_OUT' AND c.version>0 AND (c.voice_registry_version IS NULL OR c.voice_registry_version<>
                    (SELECT COALESCE(SUM(p.revision),0) FROM sales_voice_profile p WHERE p.tenant_id=c.tenant_id))
                ORDER BY c.updated_at LIMIT 25
                """);
        var banks=new java.util.HashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(var row:candidates) {
            String tenant=java.util.HexFormat.of().formatHex((byte[])row.get("tenant_id"));
            var bank=banks.computeIfAbsent(tenant,key->jdbc.queryForList("""
                    SELECT p.sales_profile_id,p.employee_name,p.embedding_json FROM sales_voice_profile p
                    JOIN sales_profile s ON s.tenant_id=p.tenant_id AND s.id=p.sales_profile_id
                    WHERE p.tenant_id=? AND p.embedding_json IS NOT NULL AND p.model='pyannote3-campplus-zh-v1'
                      AND s.status='ACTIVE' AND s.effective_from<=UTC_TIMESTAMP(6)
                      AND (s.effective_to IS NULL OR s.effective_to>UTC_TIMESTAMP(6))
                    ORDER BY p.sales_profile_id
                    """,row.get("tenant_id")));
            var vectors=bank.stream().map(p->java.util.List.of(json.readValue(p.get("embedding_json").toString(),Double[].class))).toList();
            var voices=java.util.List.of(json.readValue(row.get("profiles_json").toString(),SalesSpeechTranscriber.SpeakerProfile[].class));
            var labels=new java.util.LinkedHashMap<String,String>();
            for(var voice:voices) {
                Integer matched=com.rigour.sales.domain.VoiceIdentity.match(voice.embedding(),vectors);
                if(matched!=null)labels.put(Integer.toString(voice.id()),bank.get(matched).get("employee_name").toString());
            }
            // Registration/deletion during inference cannot commit names from an obsolete registry.
            jdbc.update("""
                    UPDATE sales_visit_speaker_context c SET c.speaker_names_json=?,c.voice_registry_version=?
                    WHERE c.tenant_id=? AND c.visit_id=? AND c.version=? AND
                    (SELECT COALESCE(SUM(p.revision),0) FROM sales_voice_profile p WHERE p.tenant_id=c.tenant_id)=?
                    """,json.writeValueAsString(labels),row.get("registry_version"),row.get("tenant_id"),row.get("visit_id"),row.get("version"),row.get("registry_version"));
        }
    }
    private static byte[] bin(UUID value){return SalesUuidCodec.encode(value);}
}
