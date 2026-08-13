package com.rigour.merchant;

import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.RunStatistics;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MerchantCrmServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_crm")
            .withUsername("rigour_crm_test")
            .withPassword("rigour_crm_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("rigour.crm.sync.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CrmMasterDataStore store;

    @Autowired
    private CrmCustomerQueryStore queryStore;

    @Test
    void contextLoadsAndMigratesAllCrmTables() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name LIKE 'crm\\_%'
                """, Integer.class)).isEqualTo(17);
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name LIKE 'crm\\_%'
                """, String.class)).contains(
                "crm_party", "crm_customer_profile", "crm_contact", "crm_address",
                "crm_external_staff", "crm_source_binding", "crm_source_identity_alias",
                "crm_sync_run", "crm_sync_checkpoint", "crm_sync_lock");
    }

    @Test
    void createsChangesSkipsAndRepairsCustomerProjection() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRecord initial = customerRecord("示例客户");

        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult created = store.importRecord(tenantId, connectorId, firstRun,
                CrmMasterDataObjectType.CUSTOMER, initial);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.CUSTOMER, created);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        assertThat(created.created()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isZero();
        assertThat(queryStore.customers(tenantId, 0, 20, "C-001", null).items())
                .singleElement().satisfies(customer -> {
                    assertThat(customer.name()).isEqualTo("示例客户");
                    assertThat(customer.phone()).isEqualTo("13800000000");
                    assertThat(customer.sourceStatus()).isEqualTo("T");
                });

        UUID duplicateRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult duplicate = store.importRecord(tenantId, connectorId, duplicateRun,
                CrmMasterDataObjectType.CUSTOMER, initial);
        finish(tenantId, connectorId, duplicateRun, CrmMasterDataObjectType.CUSTOMER, duplicate);

        assertThat(duplicate.duplicates()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isZero();

        SourceRecord changedRecord = customerRecord("示例客户（已更新）");
        UUID changedRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult changed = store.importRecord(tenantId, connectorId, changedRun,
                CrmMasterDataObjectType.CUSTOMER, changedRecord);
        finish(tenantId, connectorId, changedRun, CrmMasterDataObjectType.CUSTOMER, changed);

        assertThat(changed.changed()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isEqualTo(1);
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)).name())
                .isEqualTo("示例客户（已更新）");

        SourceRecord partial = new SourceRecord("CLIENT-GUID-1", null, null,
                null, null, null, mapOf("clientGUID", "CLIENT-GUID-1", "futureField", 43));
        UUID partialRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult partialChanged = store.importRecord(tenantId, connectorId, partialRun,
                CrmMasterDataObjectType.CUSTOMER, partial);
        finish(tenantId, connectorId, partialRun, CrmMasterDataObjectType.CUSTOMER, partialChanged);
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId))).satisfies(customer -> {
            assertThat(customer.name()).isEqualTo("示例客户（已更新）");
            assertThat(customer.phone()).isEqualTo("13800000000");
            assertThat(customer.sourceFields()).containsEntry("clientPhone", "13800000000")
                    .containsEntry("futureField", 43);
            assertThat(customer.source().clientGuid()).isEqualTo("CLIENT-GUID-1");
            assertThat(customer.source().statusCode()).isEqualTo("T");
        });

        jdbcTemplate.update("DELETE FROM crm_customer_profile WHERE tenant_id=? AND party_id=?",
                CrmUuidCodec.encode(tenantId), partyId);
        UUID repairRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult repaired = store.importRecord(tenantId, connectorId, repairRun,
                CrmMasterDataObjectType.CUSTOMER, partial);
        finish(tenantId, connectorId, repairRun, CrmMasterDataObjectType.CUSTOMER, repaired);

        assertThat(repaired.repaired()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM crm_customer_profile WHERE tenant_id=? AND party_id=?
                """, Integer.class, CrmUuidCodec.encode(tenantId), partyId)).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId))
                .as("修复缺失投影不应重复更新未变更的客户主表")
                .isEqualTo(2);
    }

    @Test
    void keepsPrimaryAndSecondarySalesAssignmentsAndSourceStaffIds() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        for (String staffId : List.of("STAFF-PRIMARY", "STAFF-SECONDARY")) {
            UUID runId = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.STAFF);
            ImportResult result = store.importRecord(tenantId, connectorId, runId,
                    CrmMasterDataObjectType.STAFF, staffRecord(staffId));
            finish(tenantId, connectorId, runId, CrmMasterDataObjectType.STAFF, result);
        }

        UUID runId = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult result = store.importRecord(tenantId, connectorId, runId,
                CrmMasterDataObjectType.CUSTOMER, customerRecordWithAssignments());
        finish(tenantId, connectorId, runId, CrmMasterDataObjectType.CUSTOMER, result);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        assertThat(jdbcTemplate.queryForList("""
                SELECT assignment_type,source_staff_id,source_name_snapshot
                  FROM crm_sales_assignment
                 WHERE tenant_id=? AND party_id=? AND status='ACTIVE'
                 ORDER BY assignment_type,source_staff_id
                """, CrmUuidCodec.encode(tenantId), partyId))
                .extracting(row -> row.get("assignment_type"), row -> row.get("source_staff_id"),
                        row -> row.get("source_name_snapshot"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PRIMARY", "STAFF-PRIMARY", "张三"),
                        org.assertj.core.groups.Tuple.tuple("SECONDARY", "STAFF-SECONDARY", "李四"));
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)).salesAssignments())
                .extracting(assignment -> assignment.assignmentType() + ":" + assignment.staffName())
                .containsExactly("PRIMARY:张三", "SECONDARY:李四");
    }

    @Test
    void keepsUnresolvedAddressSourceFieldsAndConfirmsAbsenceOnlyTwice() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRecord address = new SourceRecord("ADDR-GUID-1", "ADDR-1", "上海仓", "T", null,
                Instant.parse("2026-08-01T00:30:00Z"), mapOf(
                "addressId", "ADDR-1", "addressGuid", "ADDR-GUID-1",
                "clientGuid", "UNKNOWN-CUSTOMER", "clientNum", "C-404",
                "consignee", "上海仓", "contact", "张三", "phone", "13800000000",
                "address", "上海市浦东新区", "addressDetail", "世纪大道1号",
                "isDefault", "T", "futureField", Map.of("provider", "kept")));

        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult created = store.importRecord(tenantId, connectorId, firstRun,
                CrmMasterDataObjectType.ADDRESS, address);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.ADDRESS, created);

        Map<String, Object> binding = jdbcTemplate.queryForMap("""
                SELECT binding_status, resolution_error_code,
                       JSON_UNQUOTE(JSON_EXTRACT(source_fields_json, '$.futureField.provider')) AS future_value
                  FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
                   AND source_object_id='ADDR-GUID-1'
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(connectorId));
        assertThat(binding).containsEntry("binding_status", "UNRESOLVED")
                .containsEntry("resolution_error_code", "CUSTOMER_NOT_RESOLVED")
                .containsEntry("future_value", "kept");

        UUID absentRun1 = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        finish(tenantId, connectorId, absentRun1, CrmMasterDataObjectType.ADDRESS,
                new ImportResult(0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT_CANDIDATE");

        UUID absentRun2 = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        finish(tenantId, connectorId, absentRun2, CrmMasterDataObjectType.ADDRESS,
                new ImportResult(0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT");
    }

    private UUID start(UUID tenantId, UUID connectorId, UUID actorId,
                       CrmMasterDataObjectType type) {
        return store.startRun(tenantId, connectorId, actorId, type, 100, "TEST");
    }

    private void finish(UUID tenantId, UUID connectorId, UUID runId,
                        CrmMasterDataObjectType type, ImportResult result) {
        long fetched = result.created() + result.changed() + result.repaired()
                + result.duplicates() + result.rejected();
        store.completeRun(tenantId, connectorId, runId, type,
                new RunStatistics(fetched, result.created(), result.changed(), result.repaired(),
                        result.duplicates(), 0, result.rejected(), 1), true);
    }

    private byte[] customerTargetId(UUID tenantId, UUID connectorId) {
        return jdbcTemplate.queryForObject("""
                SELECT target_id FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='CUSTOMER'
                   AND source_object_id='CLIENT-GUID-1'
                """, byte[].class, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(connectorId));
    }

    private long partyVersion(UUID tenantId, byte[] partyId) {
        return jdbcTemplate.queryForObject("""
                SELECT version FROM crm_party WHERE tenant_id=? AND id=?
                """, Long.class, CrmUuidCodec.encode(tenantId), partyId);
    }

    private String sourcePresence(UUID tenantId, UUID connectorId) {
        return jdbcTemplate.queryForObject("""
                SELECT source_presence FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
                   AND source_object_id='ADDR-GUID-1'
                """, String.class, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(connectorId));
    }

    private static SourceRecord customerRecord(String name) {
        return new SourceRecord("CLIENT-GUID-1", "C-001", name, "T",
                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"), mapOf(
                "clientGUID", "CLIENT-GUID-1", "clientNO", "C-001", "clientAccount", "customer001",
                "clientCompanyName", name, "clientStatus", "T", "clientTrueName", "张三",
                "clientPhone", "13800000000", "clientEmail", "customer@example.com",
                "clientAdd", "上海市浦东新区世纪大道1号", "clientClearingForm", "PREPAID",
                "clientTypeName", "VIP", "clientAreaName", "华东", "clientCity", "上海",
                "Inviter", "李四", "clientAbout", "来源字段完整保留", "futureField", 42));
    }

    private static SourceRecord customerRecordWithAssignments() {
        SourceRecord base = customerRecord("含主辅业务员客户");
        Map<String, Object> fields = new LinkedHashMap<>(base.sourceFields());
        fields.put("staffID", "STAFF-PRIMARY,STAFF-SECONDARY");
        fields.put("staffName", "张三,李四");
        return new SourceRecord(base.sourceId(), base.sourceCode(), base.sourceName(), base.sourceStatus(),
                base.sourceCreatedAt(), base.sourceUpdatedAt(), fields);
    }

    private static SourceRecord staffRecord(String staffId) {
        String name = "STAFF-PRIMARY".equals(staffId) ? "张三" : "李四";
        return new SourceRecord(staffId, staffId, name, "T", null,
                Instant.parse("2026-08-01T00:00:00Z"), mapOf(
                "staff_id", staffId, "staff_name", name, "accounts_id", "ACCOUNT-" + staffId));
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
