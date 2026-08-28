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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                """, Integer.class)).isEqualTo(16);
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name LIKE 'crm\\_%'
                """, String.class)).contains(
                "crm_party", "crm_customer_profile", "crm_contact", "crm_address",
                "crm_source_binding", "crm_source_identity_alias",
                "crm_sync_run", "crm_sync_checkpoint", "crm_sync_lock");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'crm_external_staff'
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='crm_sync_run'
                   AND column_name='source_task_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND table_name='crm_sync_run'
                   AND constraint_name='chk_crm_sync_run_skipped_terminal'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void persistsFinishedSkipAuditAndRecoversOnlyExpiredUnownedRun() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID sourceTaskId = UUID.randomUUID();
        String longCode = "x".repeat(80);
        String longReason = "first\r\nsecond" + "x".repeat(2100);

        UUID skippedRun = store.recordSkippedRun(tenantId, connectorId, sourceTaskId,
                CrmMasterDataObjectType.CUSTOMER, 100, longCode, longReason);
        Map<String, Object> skipped = jdbcTemplate.queryForMap("""
                SELECT status,source_task_id,error_code,error_message,finished_at
                  FROM crm_sync_run WHERE tenant_id=? AND id=?
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(skippedRun));
        assertThat(skipped).containsEntry("status", "SKIPPED");
        assertThat(CrmUuidCodec.decode((byte[]) skipped.get("source_task_id"))).isEqualTo(sourceTaskId);
        assertThat(String.valueOf(skipped.get("error_code"))).hasSize(64);
        assertThat(String.valueOf(skipped.get("error_message"))).hasSize(2000)
                .doesNotContain("\r", "\n");
        assertThat(skipped.get("finished_at")).isNotNull();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO crm_sync_run(
                    id,tenant_id,connector_id,source_system,object_type,trigger_type,
                    sync_mode,status,page_size,max_pages,fetched_count,created_count,
                    changed_count,repaired_count,duplicate_count,absent_count,rejected_count,
                    started_at,created_at,updated_at)
                VALUES (?,?,?,'DINGHUOBAO','CUSTOMER','SCHEDULED','FULL','SKIPPED',
                        500,100,0,0,0,0,0,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, CrmUuidCodec.encode(UUID.randomUUID()), CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(connectorId)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID staleRun = store.startRun(tenantId, connectorId, null, sourceTaskId,
                CrmMasterDataObjectType.ADDRESS, 100, "SCHEDULED");
        jdbcTemplate.update("""
                UPDATE crm_sync_run SET updated_at=UTC_TIMESTAMP(6)-INTERVAL 3 HOUR
                 WHERE tenant_id=? AND id=?
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(staleRun));
        jdbcTemplate.update("""
                UPDATE crm_sync_lock SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND
                 WHERE tenant_id=? AND run_id=?
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(staleRun));

        UUID replacement = store.startRun(tenantId, connectorId, null, UUID.randomUUID(),
                CrmMasterDataObjectType.ADDRESS, 100, "SCHEDULED");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,error_code,finished_at FROM crm_sync_run
                 WHERE tenant_id=? AND id=?
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(staleRun)))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "STALE_RUN_RECOVERED")
                .satisfies(row -> assertThat(row.get("finished_at")).isNotNull());
        store.failRun(tenantId, connectorId, replacement,
                new RunStatistics(0, 0, 0, 0, 0, 0, 0, 0),
                new IllegalStateException("test cleanup"));

        UUID ownedRun = store.startRun(tenantId, connectorId, null, sourceTaskId,
                CrmMasterDataObjectType.ADDRESS, 100, "SCHEDULED");
        jdbcTemplate.update("""
                UPDATE crm_sync_run SET updated_at=UTC_TIMESTAMP(6)-INTERVAL 3 HOUR
                 WHERE tenant_id=? AND id=?
                """, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(ownedRun));

        assertThatThrownBy(() -> store.startRun(tenantId, connectorId, null, UUID.randomUUID(),
                CrmMasterDataObjectType.ADDRESS, 100, "SCHEDULED"))
                .hasMessageContaining("已有同步任务运行");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM crm_sync_run WHERE tenant_id=? AND id=?
                """, String.class, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(ownedRun)))
                .isEqualTo("RUNNING");
        store.failRun(tenantId, connectorId, ownedRun,
                new RunStatistics(0, 0, 0, 0, 0, 0, 0, 0),
                new IllegalStateException("test cleanup"));
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

        UUID runId = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult result = store.importRecord(tenantId, connectorId, runId,
                CrmMasterDataObjectType.CUSTOMER, customerRecordWithAssignments());
        finish(tenantId, connectorId, runId, CrmMasterDataObjectType.CUSTOMER, result);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        assertThat(jdbcTemplate.queryForList("""
                SELECT assignment_type,source_staff_id,iam_staff_code,iam_staff_name_snapshot,source_name_snapshot
                  FROM crm_sales_assignment
                 WHERE tenant_id=? AND party_id=? AND status='ACTIVE'
                 ORDER BY assignment_type,source_staff_id
                """, CrmUuidCodec.encode(tenantId), partyId))
                .extracting(row -> row.get("assignment_type"), row -> row.get("source_staff_id"),
                        row -> row.get("iam_staff_code"), row -> row.get("iam_staff_name_snapshot"),
                        row -> row.get("source_name_snapshot"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PRIMARY", "STAFF-PRIMARY", "RY202608220001", "张三", "张三"),
                        org.assertj.core.groups.Tuple.tuple("SECONDARY", "STAFF-SECONDARY", "RY202608220002", "李四", "李四"));
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)).salesAssignments())
                .extracting(assignment -> assignment.assignmentType() + ":"
                        + assignment.staffCode() + ":" + assignment.staffName())
                .containsExactly("PRIMARY:RY202608220001:张三", "SECONDARY:RY202608220002:李四");
    }

    @Test
    void repairsInternalCustomerTypeAndRegionWhenSourcePayloadUnchanged() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        SourceRecord type = new SourceRecord("TYPE-1", "TYPE-1", "VIP", "T",
                null, null, mapOf("typeName", "VIP"));
        UUID typeRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER_TYPE);
        ImportResult typeResult = store.importRecord(tenantId, connectorId, typeRun,
                CrmMasterDataObjectType.CUSTOMER_TYPE, type);
        finish(tenantId, connectorId, typeRun, CrmMasterDataObjectType.CUSTOMER_TYPE, typeResult);

        SourceRecord area = new SourceRecord("AREA-1", "AREA-1", "华东", "T",
                null, null, mapOf("AreaName", "华东"));
        UUID areaRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER_AREA);
        ImportResult areaResult = store.importRecord(tenantId, connectorId, areaRun,
                CrmMasterDataObjectType.CUSTOMER_AREA, area);
        finish(tenantId, connectorId, areaRun, CrmMasterDataObjectType.CUSTOMER_AREA, areaResult);

        SourceRecord customer = customerRecordWithTypeArea("TYPE-1", "AREA-1");
        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult created = store.importRecord(tenantId, connectorId, firstRun,
                CrmMasterDataObjectType.CUSTOMER, customer);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.CUSTOMER, created);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        Map<String, Object> createdRow = internalCustomerClassification(tenantId, partyId);
        assertThat(createdRow.get("customer_type_code")).isNotNull();
        assertThat(createdRow.get("region_code")).isNotNull();

        jdbcTemplate.update("""
                UPDATE crm_customer
                   SET customer_type_code=NULL, region_code=NULL
                 WHERE tenant_id=? AND party_id=?
                """, tenantId.toString(), partyId);

        UUID repairRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult repaired = store.importRecord(tenantId, connectorId, repairRun,
                CrmMasterDataObjectType.CUSTOMER, customer);
        finish(tenantId, connectorId, repairRun, CrmMasterDataObjectType.CUSTOMER, repaired);

        assertThat(repaired.repaired()).isEqualTo(1);
        assertThat(internalCustomerClassification(tenantId, partyId))
                .containsEntry("customer_type_code", createdRow.get("customer_type_code"))
                .containsEntry("region_code", createdRow.get("region_code"));
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
                new ImportResult(0, 0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT_CANDIDATE");

        UUID absentRun2 = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        finish(tenantId, connectorId, absentRun2, CrmMasterDataObjectType.ADDRESS,
                new ImportResult(0, 0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT");
    }

    @Test
    void batchImportsDuplicateAddressWithPrefetchedProjectionSnapshot() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        UUID customerRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult customer = store.importRecord(tenantId, connectorId, customerRun,
                CrmMasterDataObjectType.CUSTOMER, customerRecord("地址所属客户"));
        finish(tenantId, connectorId, customerRun, CrmMasterDataObjectType.CUSTOMER, customer);

        SourceRecord address = addressRecord("ADDR-GUID-2", "ADDR-2");
        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult created = store.importRecords(tenantId, connectorId, firstRun,
                CrmMasterDataObjectType.ADDRESS, List.of(address)).get(0);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.ADDRESS, created);
        byte[] addressId = jdbcTemplate.queryForObject("""
                SELECT target_id FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
                   AND source_object_id='ADDR-GUID-2'
                """, byte[].class, CrmUuidCodec.encode(tenantId), CrmUuidCodec.encode(connectorId));

        UUID duplicateRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult duplicate = store.importRecords(tenantId, connectorId, duplicateRun,
                CrmMasterDataObjectType.ADDRESS, List.of(address)).get(0);
        finish(tenantId, connectorId, duplicateRun, CrmMasterDataObjectType.ADDRESS, duplicate);

        assertThat(created.created()).isEqualTo(1);
        assertThat(duplicate.duplicates()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM crm_address WHERE tenant_id=? AND id=?
                """, Long.class, CrmUuidCodec.encode(tenantId), addressId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM crm_contact c
                  JOIN crm_address a ON a.tenant_id=c.tenant_id AND a.contact_id=c.id
                 WHERE a.tenant_id=? AND a.id=?
                """, Integer.class, CrmUuidCodec.encode(tenantId), addressId)).isEqualTo(1);
    }

    private UUID start(UUID tenantId, UUID connectorId, UUID actorId,
                       CrmMasterDataObjectType type) {
        return store.startRun(tenantId, connectorId, actorId, type, 100, "TEST");
    }

    private void finish(UUID tenantId, UUID connectorId, UUID runId,
                        CrmMasterDataObjectType type, ImportResult result) {
        long fetched = result.created() + result.changed() + result.repaired()
                + result.duplicates() + result.rejected() + result.unmapped();
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

    private Map<String, Object> internalCustomerClassification(UUID tenantId, byte[] partyId) {
        return jdbcTemplate.queryForMap("""
                SELECT customer_type_code, region_code
                  FROM crm_customer
                 WHERE tenant_id=? AND party_id=? AND deleted=0
                """, tenantId.toString(), partyId);
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

    private static SourceRecord customerRecordWithTypeArea(String typeSourceId, String areaSourceId) {
        SourceRecord base = customerRecord("示例客户");
        Map<String, Object> fields = new LinkedHashMap<>(base.sourceFields());
        fields.put("clientType", typeSourceId);
        fields.put("clientArea", areaSourceId);
        return new SourceRecord(base.sourceId(), base.sourceCode(), base.sourceName(), base.sourceStatus(),
                base.sourceCreatedAt(), base.sourceUpdatedAt(), fields);
    }

    private static SourceRecord customerRecordWithAssignments() {
        SourceRecord base = customerRecord("含主辅业务员客户");
        Map<String, Object> fields = new LinkedHashMap<>(base.sourceFields());
        fields.put("staffID", "STAFF-PRIMARY,STAFF-SECONDARY");
        fields.put("staffName", "张三,李四");
        fields.put("_iamStaffBySourceId", Map.of(
                "STAFF-PRIMARY", Map.of("staffCode", "RY202608220001", "staffName", "张三"),
                "STAFF-SECONDARY", Map.of("staffCode", "RY202608220002", "staffName", "李四")));
        return new SourceRecord(base.sourceId(), base.sourceCode(), base.sourceName(), base.sourceStatus(),
                base.sourceCreatedAt(), base.sourceUpdatedAt(), fields);
    }

    private static SourceRecord addressRecord(String sourceId, String addressId) {
        return new SourceRecord(sourceId, addressId, "上海仓", "T", null,
                Instant.parse("2026-08-01T00:30:00Z"), mapOf(
                "addressId", addressId, "addressGuid", sourceId,
                "clientGuid", "CLIENT-GUID-1", "clientNum", "C-001",
                "consignee", "上海仓", "contact", "张三", "phone", "13800000000",
                "address", "上海市浦东新区", "addressDetail", "世纪大道1号",
                "isDefault", "T"));
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
