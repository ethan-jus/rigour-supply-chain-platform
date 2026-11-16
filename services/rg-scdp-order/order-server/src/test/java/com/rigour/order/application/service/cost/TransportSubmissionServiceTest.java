package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

/** 使用真实 JDBC 事务核验单据、关系和凭证共同回滚，COS 使用可观测的替身。 */
class TransportSubmissionServiceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final TransportRecordService transports = mock(TransportRecordService.class);
    final OrderLogisticsCostService costs = mock(OrderLogisticsCostService.class);
    final OrderLogisticsCostStore costStore = mock(OrderLogisticsCostStore.class);
    final CostAttachmentStore files = mock(CostAttachmentStore.class);
    final CostAttachmentStorage storage = mock(CostAttachmentStorage.class);
    final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
    JdbcTemplate db;
    TransportSubmissionService service;
    boolean failMetadata;
    final byte[] pdf =
            "%PDF-1.7 shipping voucher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final List<String> uploaded = new ArrayList<>();

    @BeforeEach
    void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE cost(id VARCHAR PRIMARY KEY,revision INT,status VARCHAR,save_id"
                        + " VARCHAR,hash VARCHAR,save_revision INT)");
        db.execute(
                "CREATE TABLE evidence(id VARCHAR PRIMARY KEY,entry_id VARCHAR,name VARCHAR,type"
                        + " VARCHAR,size BIGINT,category VARCHAR,object_key VARCHAR)");
        db.execute("CREATE TABLE link(entry_id VARCHAR,order_code VARCHAR)");
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
        when(transports.detail(anyString())).thenAnswer(i -> detail(i.getArgument(0)));
        when(costStore.entry(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(i -> detail(i.getArgument(2)).record());
        when(files.list(anyString(), anyString()))
                .thenAnswer(
                        i ->
                                db.query(
                                        "SELECT * FROM evidence WHERE entry_id=? ORDER BY id",
                                        (r, n) ->
                                                new LogisticsCostAttachmentView(
                                                        r.getString("id"),
                                                        r.getString("name"),
                                                        r.getString("type"),
                                                        r.getLong("size"),
                                                        actor.toString(),
                                                        Instant.EPOCH,
                                                        r.getString("category")),
                                        i.<String>getArgument(1)));
        doAnswer(
                        i -> {
                            db.update(
                                    "DELETE FROM evidence WHERE entry_id=? AND id=?",
                                    i.<String>getArgument(1),
                                    i.<String>getArgument(2));
                            return null;
                        })
                .when(files)
                .retire(anyString(), anyString(), anyString());
        doAnswer(
                        i -> {
                            db.update(
                                    "UPDATE cost SET revision=revision+1 WHERE id=? AND revision=?",
                                    i.<String>getArgument(1),
                                    i.<Integer>getArgument(2));
                            return null;
                        })
                .when(files)
                .touch(anyString(), anyString(), anyInt(), anyString());
        doAnswer(
                        i -> {
                            LogisticsCostAttachmentView f = i.getArgument(3);
                            db.update(
                                    "INSERT INTO evidence VALUES(?,?,?,?,?,?,?)",
                                    f.id(),
                                    i.getArgument(1),
                                    f.fileName(),
                                    f.contentType(),
                                    f.size(),
                                    f.categoryCode(),
                                    i.getArgument(4));
                            files.touch(
                                    i.getArgument(0),
                                    i.getArgument(1),
                                    i.getArgument(2),
                                    f.createdBy());
                            if (failMetadata) throw new IllegalStateException("metadata failure");
                            return null;
                        })
                .when(files)
                .add(anyString(), anyString(), anyInt(), any(), anyString());
        when(files.submission(anyString(), anyString()))
                .thenAnswer(
                        i -> {
                            var rows =
                                    db.query(
                                            "SELECT id,hash,save_revision FROM cost WHERE"
                                                    + " save_id=?",
                                            (r, n) ->
                                                    new CostAttachmentStore.Submission(
                                                            r.getString(1),
                                                            r.getString(2),
                                                            r.getInt(3)),
                                            i.<String>getArgument(1));
                            return rows.isEmpty() ? null : rows.getFirst();
                        });
        doAnswer(
                        i -> {
                            db.update(
                                    "UPDATE cost SET save_id=?,hash=?,save_revision=? WHERE id=?",
                                    i.getArgument(2),
                                    i.getArgument(3),
                                    i.getArgument(4),
                                    i.getArgument(1));
                            return null;
                        })
                .when(files)
                .completed(anyString(), anyString(), anyString(), anyString(), anyInt());
        when(transports.saveWithPendingEvidence(any(), any()))
                .thenAnswer(
                        i -> {
                            if (i.getArgument(0) == null) {
                                db.update("INSERT INTO cost VALUES('C1',1,'DRAFT',NULL,NULL,NULL)");
                                db.update("INSERT INTO link VALUES('C1','SO1')");
                            } else db.update("UPDATE cost SET revision=revision+1 WHERE id='C1'");
                            return detail("C1");
                        });
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        i -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var key =
                                    tenant
                                            + "/order-cost-attachments/request/"
                                            + UUID.randomUUID()
                                            + ".pdf";
                            uploaded.add(key);
                            return key;
                        });
        var evidence = new CostAttachmentService(costStore, files, storage);
        service =
                new TransportSubmissionService(
                        transports,
                        costs,
                        evidence,
                        files,
                        json,
                        storage,
                        new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    Detail detail(String id) {
        int revision = db.queryForObject("SELECT revision FROM cost WHERE id=?", Integer.class, id);
        String status = db.queryForObject("SELECT status FROM cost WHERE id=?", String.class, id);
        var record =
                json.readValue(
                        "{\"id\":\""
                                + id
                                + "\",\"revision\":"
                                + revision
                                + ",\"statusCode\":\""
                                + status
                                + "\",\"data\":{},\"sources\":[]}",
                        LogisticsCostDetailView.class);
        return new Detail(record, null);
    }

    TransportSubmission command(
            Integer revision, List<TransportSubmission.Upload> uploads, List<String> removed) {
        var draft =
                json.readValue(
                        "{\"revision\":"
                                + revision
                                + ",\"requestId\":\""
                                + UUID.randomUUID()
                                + "\",\"transportType\":\"DELIVERY\",\"date\":\"2026-11-12\",\"carrierCode\":\"SF\"}",
                        Draft.class);
        return new TransportSubmission(UUID.randomUUID().toString(), draft, null, uploads, removed);
    }

    TransportSubmissionService.Content content() {
        return new TransportSubmissionService.Content("凭证.pdf", "application/pdf", pdf);
    }

    TransportSubmission.Upload upload() {
        return new TransportSubmission.Upload("EXPENSE_VOUCHER", null);
    }

    int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void storesActualCosKeysCategoriesAndRetriesWithoutDuplicateUploads() {
        var command = command(null, List.of(upload()), List.of());
        var first = service.save(null, command, List.of(content()));
        assertThat(first.record().revision()).isEqualTo(2);
        assertThat(count("cost")).isEqualTo(1);
        assertThat(count("link")).isEqualTo(1);
        assertThat(count("evidence")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT object_key FROM evidence", String.class))
                .isEqualTo(uploaded.getFirst());
        assertThat(db.queryForObject("SELECT category FROM evidence", String.class))
                .isEqualTo("EXPENSE_VOUCHER");
        assertThat(service.save(null, command, List.of(content())).record().revision())
                .isEqualTo(2);
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
        verify(storage, never()).discard(anyString());
    }

    @Test
    void missingEvidenceAndFabricatedFileNeverCreateARecord() {
        assertThatThrownBy(() -> service.save(null, command(null, List.of(), List.of()), List.of()))
                .hasMessageContaining("至少上传一份");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload()), List.of()),
                                        List.of(
                                                new TransportSubmissionService.Content(
                                                        "伪造.png", "image/png", pdf))))
                .hasMessageContaining("有效的");
        assertThat(count("cost")).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void missingPermissionNeverUploadsOrCreatesData() {
        TestAuthorizationContext.clear();
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload()), List.of()),
                                        List.of(content())))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(storage);
        assertThat(count("cost")).isZero();
    }

    @Test
    void uncertainDatabaseCommitMustNotDeletePossiblyReferencedCosObjects() {
        var manager =
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    protected void doBegin(
                            Object transaction,
                            org.springframework.transaction.TransactionDefinition definition) {}

                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {
                        throw new org.springframework.transaction.TransactionSystemException(
                                "commit outcome unknown");
                    }

                    protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {}
                };
        service =
                new TransportSubmissionService(
                        transports,
                        costs,
                        new CostAttachmentService(costStore, files, storage),
                        files,
                        json,
                        storage,
                        manager);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload()), List.of()),
                                        List.of(content())))
                .hasMessageContaining("commit outcome unknown");
        verify(storage, never()).discard(anyString());
    }

    @Test
    void uploadFailureDiscardsAlreadyUploadedObjectsWithoutCreatingCostOrLinks() {
        when(storage.upload(anyString(), anyString(), anyString(), any()))
                .thenReturn(tenant + "/order-cost-attachments/first.pdf")
                .thenThrow(new IllegalStateException("COS timeout"));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload(), upload()), List.of()),
                                        List.of(content(), content())))
                .hasMessageContaining("COS timeout");
        assertThat(count("cost")).isZero();
        assertThat(count("link")).isZero();
        verify(storage).discard(tenant + "/order-cost-attachments/first.pdf");
    }

    @Test
    void metadataFailureRollsBackCostLinksAndEvidenceAndDiscardsOnlyNewObjects() {
        failMetadata = true;
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload()), List.of()),
                                        List.of(content())))
                .hasMessageContaining("metadata failure");
        for (var table : List.of("cost", "link", "evidence")) assertThat(count(table)).isZero();
        verify(storage).discard(uploaded.getFirst());
    }

    @Test
    void replacingLastVoucherIsAtomicAndRemovingLastVoucherIsRejected() {
        service.save(null, command(null, List.of(upload()), List.of()), List.of(content()));
        var old = db.queryForObject("SELECT id FROM evidence", String.class);
        assertThatThrownBy(() -> service.save("C1", command(2, List.of(), List.of(old)), List.of()))
                .hasMessageContaining("至少上传一份");
        failMetadata = true;
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "C1",
                                        command(
                                                2,
                                                List.of(
                                                        new TransportSubmission.Upload(
                                                                "RECEIPT", old)),
                                                List.of()),
                                        List.of(content())))
                .hasMessageContaining("metadata failure");
        assertThat(db.queryForObject("SELECT id FROM evidence", String.class)).isEqualTo(old);
        assertThat(detail("C1").record().revision()).isEqualTo(2);
        verify(storage, never()).discard(uploaded.getFirst());
        failMetadata = false;
        service.save(
                "C1",
                command(2, List.of(new TransportSubmission.Upload("RECEIPT", old)), List.of()),
                List.of(content()));
        assertThat(count("evidence")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT category FROM evidence", String.class))
                .isEqualTo("RECEIPT");
    }

    @Test
    void newReceiptCannotAppendAnotherBatchToAlreadyCreatedRequest() {
        service.save(null, command(null, List.of(upload()), List.of()), List.of(content()));
        doAnswer(i -> detail("C1")).when(transports).saveWithPendingEvidence(isNull(), any());
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command(null, List.of(upload()), List.of()),
                                        List.of(content())))
                .hasMessageContaining("已经保存");
        assertThat(count("cost")).isEqualTo(1);
        assertThat(count("evidence")).isEqualTo(1);
        assertThat(detail("C1").record().revision()).isEqualTo(2);
        verify(storage).discard(uploaded.getLast());
        verify(storage, never()).discard(uploaded.getFirst());
    }

    @Test
    void confirmedOrStaleRecordCannotUploadAndModifiedReplayIsRejected() {
        var command = command(null, List.of(upload()), List.of());
        service.save(null, command, List.of(content()));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        null,
                                        command,
                                        List.of(
                                                new TransportSubmissionService.Content(
                                                        "新凭证.pdf", "application/pdf", pdf))))
                .hasMessageContaining("内容或记录已变化");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "C1",
                                        command(1, List.of(upload()), List.of()),
                                        List.of(content())))
                .hasMessageContaining("记录已变化");
        db.update("UPDATE cost SET status='CONFIRMED',revision=3");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "C1",
                                        command(3, List.of(upload()), List.of()),
                                        List.of(content())))
                .hasMessageContaining("不是待确认");
        verify(storage, times(1)).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void salesOrderIsRevalidatedAfterCosUploadAndCancellationRollsBackEverything() {
        var sales = mock(SalesLogisticsService.class);
        service.salesLogistics(sales);
        var original = command(null, List.of(upload()), List.of());
        var withSale =
                new TransportSubmission(
                        original.submissionId(),
                        original.draft(),
                        original.advanced(),
                        original.uploads(),
                        original.removedIds(),
                        11L);
        doNothing()
                .doThrow(new IllegalArgumentException("销售单已取消"))
                .when(sales)
                .validateManual(11L, original.draft());
        assertThatThrownBy(() -> service.save(null, withSale, List.of(content())))
                .hasMessageContaining("销售单已取消");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cost", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isZero();
        verify(storage).discard(anyString());
        verify(sales, times(2)).validateManual(11L, original.draft());
    }
}
