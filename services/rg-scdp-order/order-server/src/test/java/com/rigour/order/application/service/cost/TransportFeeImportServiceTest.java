package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportFeeImportModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class TransportFeeImportServiceTest {
    final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
    final JsonMapper json = JsonMapper.builder().build();
    final OrderLogisticsCostStore costs = mock(OrderLogisticsCostStore.class);
    final PlatformTransportStore platform = mock(PlatformTransportStore.class);
    final ShipmentTransportStore shipments = mock(ShipmentTransportStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final TransportFeeArchiveStore archives = mock(TransportFeeArchiveStore.class);
    JdbcTemplate db;
    TransportFeeImportService service;
    boolean failEvidence;

    @BeforeEach
    void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE cost(id VARCHAR PRIMARY KEY, data_json CLOB,revision INT)");
        db.execute("CREATE TABLE proof(id VARCHAR,entry_id VARCHAR,file_key VARCHAR)");
        var data =
                json.readValue(
                        """
                        {"requestId":"saved","amount":null,"costDate":"2026-11-16","paymentStatusCode":"UNKNOWN","ledger":{
                         "schemaVersion":2,"transportPurposeCode":"DELIVERY","platformOrderNo":"SF001","carrierCode":"SF","shippingTime":"2026-11-16T10:22:33",
                         "scopeWarehouseIds":[],"scopeCityCodes":[],"receivingPoints":[],"feeItems":[{"feeKey":"freight","feeTypeCode":"FREIGHT","feeAmount":null,"bearerCode":"COMPANY","ourAmount":null,"allocationMethodCode":"EQUAL","remark":"费用备注"}],
                         "sources":[{"orderCode":"SO1","orderTypeCode":"SALES_ORDER","linkRoleCode":"COST","documentAmount":1,"employeeCode":"E1","departmentId":7,"allocations":[{"feeKey":"freight","receivingPointCode":"TO0","cityCode":"WH","basis":1,"amount":0}]},
                           {"orderCode":"SO2","orderTypeCode":"SALES_ORDER","linkRoleCode":"COST","documentAmount":2,"employeeCode":"E2","departmentId":8,"allocations":[{"feeKey":"freight","receivingPointCode":"TO1","cityCode":"WH","basis":2,"amount":0}]}]}}
                        """,
                        LogisticsCostCommand.class);
        db.update("INSERT INTO cost VALUES('C1',?,1)", json.writeValueAsString(data));
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:logistics-cost:write", "order:logistics-cost:read")));
        when(costs.trackingCandidates(anyString(), anyList(), any(), anyString()))
                .thenAnswer(
                        i ->
                                i.<List<String>>getArgument(1).contains("SF001")
                                        ? List.of(current())
                                        : List.of());
        when(costs.entry(anyString(), anyString(), eq("C1"), anyString()))
                .thenAnswer(i -> current());
        when(costs.save(
                        anyString(),
                        anyString(),
                        eq("C1"),
                        any(),
                        anyList(),
                        any(),
                        any(),
                        anyString(),
                        anyString()))
                .thenAnswer(
                        i -> {
                            db.update(
                                    "UPDATE cost SET data_json=?,revision=revision+1 WHERE id='C1'",
                                    json.writeValueAsString(i.getArgument(3)));
                            return current();
                        });
        when(platform.byCosts(anyString(), anyList())).thenReturn(Map.of());
        when(shipments.byCosts(anyString(), anyList())).thenReturn(Map.of());
        when(files.list(anyString(), anyString())).thenReturn(List.of());
        doAnswer(
                        i -> {
                            var f = i.<LogisticsCostAttachmentView>getArgument(3);
                            db.update(
                                    "INSERT INTO proof VALUES(?,?,?)",
                                    f.id(),
                                    i.getArgument(1),
                                    i.getArgument(4));
                            db.update("UPDATE cost SET revision=revision+1 WHERE id='C1'");
                            if (failEvidence) throw new IllegalStateException("metadata failure");
                            return null;
                        })
                .when(files)
                .add(anyString(), anyString(), anyInt(), any(), anyString());
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenReturn(tenant + "/order-cost-attachments/import-object");
        when(archives.find(anyString(), anyString(), anyString()))
                .thenReturn(
                        new TransportFeeArchiveStore.Archive(
                                "private-batch", "private-original-key"));
        service =
                new TransportFeeImportService(
                        costs,
                        platform,
                        shipments,
                        storage,
                        files,
                        new CostAttachmentService(costs, files, storage),
                        new DataSourceTransactionManager(ds),
                        archives);
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    LogisticsCostDetailView current() {
        return db.queryForObject(
                "SELECT * FROM cost WHERE id='C1'",
                (r, n) ->
                        new LogisticsCostDetailView(
                                "C1",
                                "COST1",
                                "LOGISTICS",
                                "ACTIVE",
                                r.getInt("revision"),
                                json.readValue(
                                        r.getString("data_json"), LogisticsCostCommand.class),
                                List.of(),
                                null,
                                null,
                                java.time.Instant.EPOCH,
                                java.time.Instant.EPOCH));
    }

    byte[] file(String rows) {
        return TransportFeeWorkbookReaderTest.workbook(rows);
    }

    byte[] file() {
        return file(TransportFeeWorkbookReaderTest.row(2, "SF001", "10.01", "账单补录"));
    }

    Apply command(byte[] b) {
        var p = service.preview(b, null);
        var i = p.items().getFirst();
        return new Apply(
                p.fileHash(), null, List.of(new Selection(i.line(), i.costId(), i.revision())));
    }

    @Test
    void savesMoneyExactAllocationAndEvidenceTogetherAndRepeatDoesNotAddFeeOrProof() {
        var bytes = file();
        var command = command(bytes);
        var result = service.apply("费用.xlsx", bytes, command);
        assertThat(result.items().getFirst().state()).isEqualTo("IMPORTED");
        var saved = current();
        assertThat(saved.data().amount()).isEqualByComparingTo("10.01");
        assertThat(saved.data().ledger().sources().get(0).allocations().getFirst().amount())
                .isEqualByComparingTo("3.34");
        assertThat(saved.data().ledger().sources().get(1).allocations().getFirst().amount())
                .isEqualByComparingTo("6.67");
        assertThat(saved.data().ledger().shippingTime().toString())
                .isEqualTo("2026-11-16T10:22:33");
        assertThat(saved.data().ledger().feeItems().getFirst().remark()).isEqualTo("费用备注");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM proof", Integer.class)).isEqualTo(1);
        assertThat(service.apply("费用.xlsx", bytes, command).items().getFirst().state())
                .isEqualTo("UNCHANGED");
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
        verify(storage, never()).discard(anyString());
    }

    @Test
    void metadataFailureRollsBackFeeAndRetryCanComplete() {
        byte[] bytes = file();
        var c = command(bytes);
        failEvidence = true;
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state())
                .isEqualTo("ERROR");
        assertThat(current().data().amount()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM proof", Integer.class)).isZero();
        verify(storage).discard(anyString());
        failEvidence = false;
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state())
                .isEqualTo("IMPORTED");
    }

    @Test
    void cosEvidenceFailureReturnsRowErrorAndRetryNeverDuplicatesCost() {
        byte[] bytes = file();
        var c = command(bytes);
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("COS unavailable"));
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state()).isEqualTo("ERROR");
        assertThat(current().data().amount()).isNull();
        doReturn(tenant + "/order-cost-attachments/retry-proof").when(storage).upload(anyString(), anyString(), anyString(), any());
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state()).isEqualTo("IMPORTED");
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state()).isEqualTo("UNCHANGED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM proof", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRevisionChangeDoesNotUploadOrOverwrite() {
        byte[] bytes = file();
        var c = command(bytes);
        db.update("UPDATE cost SET revision=revision+1");
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state())
                .isEqualTo("CONFLICT");
        verifyNoInteractions(storage);
    }

    @Test
    void existingDifferentFeeNeverOverwritten() {
        byte[] bytes = file();
        var c = command(bytes);
        var data =
                current()
                        .data()
                        .withLedger(current().data().ledger(), new java.math.BigDecimal("20"));
        db.update("UPDATE cost SET data_json=?", json.writeValueAsString(data));
        assertThat(service.apply("费用.xlsx", bytes, c).items().getFirst().state())
                .isEqualTo("CONFLICT");
        verifyNoInteractions(storage);
    }

    @Test
    void duplicateFileNumbersAndUnmatchedRowsAreNotEligible() {
        var rows =
                service.preview(
                                file(
                                        TransportFeeWorkbookReaderTest.row(2, "SF001", "1", "")
                                                + TransportFeeWorkbookReaderTest.row(
                                                        3, "SF001", "2", "")
                                                + TransportFeeWorkbookReaderTest.row(
                                                        4, "UNKNOWN", "3", "")),
                                null)
                        .items();
        assertThat(rows).allSatisfy(i -> assertThat(i.state()).isEqualTo("ERROR"));
        verifyNoInteractions(storage);
    }

    @Test
    void lacksPermissionOrChangesFileCannotWrite() {
        byte[] bytes = file();
        var c = command(bytes);
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        "费用.xlsx",
                                        file(
                                                TransportFeeWorkbookReaderTest.row(
                                                        2, "SF001", "22", "")),
                                        c))
                .hasMessageContaining("核对");
        TestAuthorizationContext.clear();
        assertThatThrownBy(() -> service.preview(bytes, null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storage);
    }
}
