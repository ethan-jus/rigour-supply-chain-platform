package com.rigour.integration.infrastructure.thirdparty.huolala.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaDomainClient;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class HuolalaEvidencePersistenceTest {
    JdbcTemplate jdbc;
    final ProductMediaStorage files = mock(ProductMediaStorage.class);
    final HuolalaDomainClient domains = mock(HuolalaDomainClient.class);
    final HttpClient http = mock(HttpClient.class);
    HuolalaVoucherWorker worker;
    final UUID tenant = UUID.randomUUID();
    final JsonMapper json = JsonMapper.builder().build();
    final CarrierOrder order =
            new CarrierOrder(
                    "1001",
                    "3624071813921329726",
                    "已完成",
                    "2026-11-08",
                    null,
                    Map.of(),
                    List.of(),
                    0);

    @BeforeEach
    void setup() throws Exception {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                new String(
                        getClass()
                                .getResourceAsStream("/db/migration/V29__huolala_voucher_jobs.sql")
                                .readAllBytes(),
                        StandardCharsets.UTF_8));
        worker =
                spy(
                        new HuolalaVoucherWorker(
                                jdbc,
                                files,
                                domains,
                                Runnable::run,
                                new ProductMediaProperties(),
                                "*.huolala.cn",
                                http));
        // 下载由HttpClient测试替身响应；URL安全规则由HuolalaVoucherWorkerTest独立覆盖。
        doReturn(URI.create("https://luna-cdn.huolala.cn/a.png"))
                .when(worker)
                .validateUri(anyString());
    }

    void enqueueOne() {
        worker.enqueue(
                tenant,
                "C1",
                order,
                json.readTree(
                        "{\"order_detail_item\":{\"receipt_url\":[\"https://luna-cdn.huolala.cn/receipt.jpg?signature=old\"]}}"));
    }

    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> image() {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenAnswer(
                        i ->
                                new ByteArrayInputStream(
                                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}));
        return response;
    }

    void due() {
        jdbc.update(
                "UPDATE integration_huolala_voucher SET next_attempt=TIMESTAMP '2000-01-01"
                        + " 00:00:00'");
    }

    @Test
    void enterpriseEvidenceIsPersistedIdempotentlyAndEachFileIsCopiedToOwnTenantCos()
            throws Exception {
        var detail =
                json.readTree(
                        getClass()
                                .getResourceAsStream("/huolala/enterprise-detail-evidence.json")
                                .readAllBytes());
        worker.enqueue(tenant, "C1", order, detail);
        worker.enqueue(tenant, "C1", order, detail);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher", Integer.class))
                .isEqualTo(6);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(i -> image());
        worker.dispatch();
        assertThat(jdbc.queryForList("SELECT state FROM integration_huolala_voucher", String.class))
                .containsOnly("DONE");
        verify(files, times(6))
                .put(
                        eq(tenant.toString()),
                        startsWith(
                                tenant + "/integration-huolala-vouchers/C1/3624071813921329726/"),
                        anyString(),
                        eq("image/png"),
                        any(byte[].class));
        worker.enqueue(tenant, "C1", order, detail);
        worker.processOne();
        verify(http, times(6)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(domains, never()).receive(any(), any());
        worker.enqueue(UUID.randomUUID(), "C1", order, detail);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher", Integer.class))
                .isEqualTo(12);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedDownloadRetainsEvidenceAndRetriesAfterTwelveAttemptsAndSignatureRotation(
            boolean rotated) throws Exception {
        enqueueOne();
        jdbc.update("UPDATE integration_huolala_voucher SET attempts=12");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("unavailable"));
        worker.processOne();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT state,attempts,file_key FROM integration_huolala_voucher"))
                .containsEntry("STATE", "FAILED")
                .containsEntry("ATTEMPTS", 13)
                .containsEntry("FILE_KEY", null);
        verify(domains).voucher(any(), argThat(u -> "FAILED".equals(u.voucher().state())));
        if (rotated) {
            worker.enqueue(
                    tenant,
                    "C1",
                    order,
                    json.readTree(
                            "{\"order_detail_item\":{\"receipt_url\":[\"https://luna-cdn.huolala.cn/receipt.jpg?signature=fresh\"]}}"));
        } else due();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher", Integer.class))
                .isEqualTo(1);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(i -> image());
        worker.processOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM integration_huolala_voucher", String.class))
                .isEqualTo("DONE");
        verify(files)
                .put(
                        eq(tenant.toString()),
                        anyString(),
                        anyString(),
                        eq("image/png"),
                        any(byte[].class));
    }

    @Test
    void orderDeliveryFailureReusesUploadedObjectWithoutDownloadingOrChargingAgain()
            throws Exception {
        enqueueOne();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(i -> image());
        doThrow(new IllegalStateException("Order不可用"))
                .doNothing()
                .when(domains)
                .voucher(any(), argThat(u -> "READY".equals(u.voucher().state())));
        worker.processOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT file_key FROM integration_huolala_voucher", String.class))
                .startsWith(tenant + "/integration-huolala-vouchers/");
        due();
        worker.processOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM integration_huolala_voucher", String.class))
                .isEqualTo("DONE");
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(files).put(anyString(), anyString(), anyString(), anyString(), any(byte[].class));
        verify(domains, never()).receive(any(), any());
    }

    @Test
    void failedAssetDoesNotBlockTheOtherFilesAndTheNextBatchOnlyRetriesThatAsset()
            throws Exception {
        var detail =
                json.readTree(
                        getClass()
                                .getResourceAsStream("/huolala/enterprise-detail-evidence.json")
                                .readAllBytes());
        worker.enqueue(tenant, "C1", order, detail);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("暂不可用"))
                .thenAnswer(i -> image());
        worker.dispatch();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher WHERE"
                                        + " state='FAILED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher WHERE"
                                        + " state='DONE'",
                                Integer.class))
                .isEqualTo(5);
        due();
        worker.dispatch();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM integration_huolala_voucher WHERE"
                                        + " state='DONE'",
                                Integer.class))
                .isEqualTo(6);
        verify(files, times(6))
                .put(anyString(), anyString(), anyString(), anyString(), any(byte[].class));
        verify(http, times(7)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
