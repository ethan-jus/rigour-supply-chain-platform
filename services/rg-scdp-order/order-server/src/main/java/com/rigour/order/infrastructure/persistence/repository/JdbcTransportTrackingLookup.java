package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.model.ShipmentTransportModels.Command;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 同步写入持有租户运输成本锁后，通过实际单号查找有效费用单。 */
final class JdbcTransportTrackingLookup {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcTransportTrackingLookup(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static List<String> numbers(Collection<String> values) {
        return values.stream().map(JdbcTransportTrackingLookup::normalize)
                .filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    record Match(String costId, String carrierCode, boolean huolalaOrder, boolean adoptableByHuolala) {}

    boolean linkedHuolala(String tenant, String costId, String number) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_logistics_import_order WHERE tenant_id=? AND cost_id=?"
                        + " AND source_channel='API' AND UPPER(TRIM(carrier_order_no))=?",
                Integer.class, tenant, costId, number) > 0;
    }

    List<Match> find(String tenant, List<String> wanted) {
        if (wanted.isEmpty()) return List.of();
        String in = String.join(",", Collections.nCopies(wanted.size(), "?"));
        String shipmentSearch = String.join(" OR ", Collections.nCopies(
                wanted.size(), "LOCATE(?, UPPER(JSON_EXTRACT(s.latest_json,'$.trackingNumbers')))>0"));
        String platformNo = "JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.ledger.platformOrderNo'))";
        String ledgerNo = "JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.ledger.trackingNo'))";
        String legacyNo = "JSON_UNQUOTE(JSON_EXTRACT(c.data_json,'$.logistics.trackingNo'))";
        var args = new ArrayList<Object>();
        args.add(tenant);
        for (int i = 0; i < 5; i++) args.addAll(wanted);
        for (String no : wanted) {
            String quoted = json.writeValueAsString(no);
            args.add(quoted.substring(1, quoted.length() - 1).toUpperCase(Locale.ROOT));
        }
        var candidates = new LinkedHashMap<String, Match>();
        jdbc.query(
                "SELECT c.id,c.carrier_code,c.source_system,c.tracking_no," + platformNo
                        + " platform_no," + ledgerNo + " ledger_no," + legacyNo + " legacy_no,"
                        + " i.carrier_order_no,i.source_channel,s.latest_json"
                        + " FROM order_logistics_cost c LEFT JOIN order_logistics_import_order i"
                        + " ON i.tenant_id=c.tenant_id AND i.cost_id=c.id"
                        + " LEFT JOIN order_transport_shipment_source s ON s.tenant_id=c.tenant_id"
                        + " AND s.cost_id=c.id AND s.platform_code='DHB'"
                        + " WHERE c.tenant_id=? AND c.category_code='LOGISTICS' AND c.deleted=0"
                        + " AND c.status_code='ACTIVE' AND (UPPER(TRIM(c.tracking_no)) IN (" + in + ")"
                        + " OR UPPER(TRIM(" + platformNo + ")) IN (" + in + ")"
                        + " OR UPPER(TRIM(" + ledgerNo + ")) IN (" + in + ")"
                        + " OR UPPER(TRIM(" + legacyNo + ")) IN (" + in + ")"
                        + " OR UPPER(TRIM(i.carrier_order_no)) IN (" + in + ") OR " + shipmentSearch + ")",
                rs -> {
                    String shipmentJson = rs.getString("latest_json");
                    var shipment = shipmentJson == null ? null : json.readValue(shipmentJson, Command.class);
                    var shipmentNumbers = shipment == null ? List.<String>of() : numbers(shipment.trackingNumbers());
                    boolean onShipment = shipmentNumbers.stream().anyMatch(wanted::contains);
                    boolean onImport = contains(wanted, rs.getString("carrier_order_no"));
                    boolean onPlatform = contains(wanted, rs.getString("platform_no"));
                    if (!onShipment && !onImport && !onPlatform
                            && !contains(wanted, rs.getString("tracking_no"))
                            && !contains(wanted, rs.getString("ledger_no"))
                            && !contains(wanted, rs.getString("legacy_no"))) return;
                    String id = rs.getString("id");
                    String carrier = rs.getString("carrier_code");
                    String source = rs.getString("source_system");
                    boolean huolala = onImport && "API".equals(rs.getString("source_channel"))
                            && "HUOLALA".equals(source) && "HLL".equals(carrier);
                    boolean adoptable = !"HUOLALA".equals(source) && ("DHB".equals(source)
                            ? shipment != null && shipmentNumbers.equals(wanted)
                                    && !"SELF".equals(shipment.deliveryModeCode())
                                    && ("HLL".equals(carrier)
                                            || carrier == null && JdbcShipmentTransportStore.carrierNotProvided(shipment))
                            : "HLL".equals(carrier) && (onPlatform || onImport));
                    var previous = candidates.get(id);
                    candidates.put(id, new Match(id, carrier,
                            huolala || previous != null && previous.huolalaOrder(),
                            adoptable || previous != null && previous.adoptableByHuolala()));
                }, args.toArray());
        return List.copyOf(candidates.values());
    }

    private static boolean contains(List<String> numbers, String value) {
        String normalized = normalize(value);
        return normalized != null && numbers.contains(normalized);
    }
}
