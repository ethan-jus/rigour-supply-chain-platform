package com.rigour.integration.infrastructure.persistence.repository;

import com.rigour.integration.application.port.out.DictionarySourceMappingStore;
import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.api.v1.model.DictionarySourceMappingCommand;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Integration 来源字典映射的单库实现，所有查改均限定租户。 */
@Repository
public class JdbcDictionarySourceMappingRepository implements DictionarySourceMappingStore {
    private final JdbcTemplate jdbc;
    public JdbcDictionarySourceMappingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String MAPPED_DHB_CARRIER = """
            source_system='DHB' AND source_scope='DHB_SHIPMENT_LOGISTICS'
                AND dictionary_code='COST_CARRIER' AND source_field='shipment.logistics'
                AND mapping_status='MAPPED' AND target_dictionary_code='COST_CARRIER'
                AND target_item_code IS NOT NULL AND target_item_code<>'SELF'
            """;

    @Override
    public List<DictionarySourceMappingView> mappedShipmentCarriers(String tenant, List<String> values) {
        if (values.isEmpty()) return List.of();
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(tenant);
        parameters.addAll(values);
        return jdbc.query("SELECT * FROM integration_dictionary_mapping WHERE tenant_id=? AND "
                + MAPPED_DHB_CARRIER + " AND carrier_backfill_revision<revision AND source_value IN ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")",
                this::view, parameters.toArray());
    }

    @Override
    public List<MappedShipmentCarrier> pendingShipmentCarrierBackfills(long id, int limit) {
        return jdbc.query("SELECT * FROM integration_dictionary_mapping WHERE id>? AND "
                + MAPPED_DHB_CARRIER + " AND carrier_backfill_revision<revision ORDER BY id LIMIT ?",
                (rs, row) -> new MappedShipmentCarrier(rs.getString("tenant_id"), view(rs, row)),
                id, limit);
    }

    @Override
    public boolean lockShipmentCarrierBackfill(String tenant, long id, int revision) {
        return !jdbc.queryForList("""
                SELECT id FROM integration_dictionary_mapping
                WHERE tenant_id=? AND id=? AND revision=? AND carrier_backfill_revision<revision
                FOR UPDATE
                """, Long.class, tenant, id, revision).isEmpty();
    }

    @Override
    public void completeShipmentCarrierBackfill(String tenant, long id, int revision) {
        jdbc.update("""
                UPDATE integration_dictionary_mapping SET carrier_backfill_revision=?
                WHERE tenant_id=? AND id=? AND revision=? AND carrier_backfill_revision<revision
                """, revision, tenant, id, revision);
    }

    @Override public List<DictionarySourceMappingView> list(String tenant, String dictionary) {
        return jdbc.query("SELECT * FROM integration_dictionary_mapping WHERE tenant_id=? AND dictionary_code=? ORDER BY mapping_status DESC,source_system,source_scope,source_field,id", this::view, tenant, dictionary);
    }
    @Override public Optional<DictionarySourceMappingView> find(String tenant, Long id) {
        return jdbc.query("SELECT * FROM integration_dictionary_mapping WHERE tenant_id=? AND id=?", this::view, tenant, id).stream().findFirst();
    }
    @Override public Optional<DictionarySourceMappingView> find(Key key) {
        return jdbc.query("""
                SELECT * FROM integration_dictionary_mapping WHERE tenant_id=? AND source_system=? AND source_scope=?
                AND dictionary_code=? AND source_field=? AND source_value_hash=UNHEX(SHA2(?,256)) AND source_value=?
                """, this::view, key.tenant(),key.system(),key.scope(),key.dictionary(),key.field(),key.value(),key.value()).stream().findFirst();
    }
    @Override public void observe(Key key, String targetDictionary, String targetCode) {
        jdbc.update("""
                INSERT INTO integration_dictionary_mapping
                (tenant_id,source_system,source_scope,dictionary_code,source_field,source_value,source_value_hash,
                 target_dictionary_code,target_item_code,mapping_status,first_seen,last_seen,created_by,updated_by)
                VALUES (?,?,?,?,?,?,UNHEX(SHA2(?,256)),?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'SYSTEM','SYSTEM')
                ON DUPLICATE KEY UPDATE
                  revision=revision+IF(mapping_status<>VALUES(mapping_status) OR (manual_override=0 AND
                    (NOT (target_dictionary_code <=> VALUES(target_dictionary_code)) OR NOT (target_item_code <=> VALUES(target_item_code)))),1,0),
                  target_dictionary_code=IF(manual_override=1,target_dictionary_code,VALUES(target_dictionary_code)),
                  target_item_code=IF(manual_override=1,target_item_code,VALUES(target_item_code)),
                  mapping_status=VALUES(mapping_status),last_seen=UTC_TIMESTAMP(6),updated_by='SYSTEM'
                """, key.tenant(),key.system(),key.scope(),key.dictionary(),key.field(),key.value(),key.value(),
                targetDictionary,targetCode,targetCode==null ? "PENDING" : "MAPPED");
    }
    @Override public DictionarySourceMappingView update(String tenant, Long id, DictionarySourceMappingCommand command, String actor) {
        int changed=jdbc.update("""
                UPDATE integration_dictionary_mapping SET target_dictionary_code=?,target_item_code=?,mapping_status='MAPPED',
                  manual_override=1,revision=revision+1,updated_by=? WHERE tenant_id=? AND id=? AND revision=?
                """, command.targetDictionaryCode(),command.targetItemCode(),actor,tenant,id,command.revision());
        if (changed!=1) throw new BusinessException(ErrorCode.CONFLICT,"来源映射已变化或不存在，请刷新",List.of());
        return find(tenant,id).orElseThrow();
    }
    private DictionarySourceMappingView view(ResultSet r, int row) throws SQLException {
        return new DictionarySourceMappingView(r.getLong("id"),r.getString("source_system"),r.getString("source_scope"),
                r.getString("dictionary_code"),r.getString("source_field"),r.getString("source_value"),
                r.getString("target_dictionary_code"),r.getString("target_item_code"),r.getString("mapping_status"),
                r.getBoolean("manual_override"),r.getInt("revision"),r.getTimestamp("first_seen").toLocalDateTime(),r.getTimestamp("last_seen").toLocalDateTime());
    }
}
