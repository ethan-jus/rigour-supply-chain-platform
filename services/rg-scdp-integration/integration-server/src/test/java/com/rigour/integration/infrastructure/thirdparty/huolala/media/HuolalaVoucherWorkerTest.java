package com.rigour.integration.infrastructure.thirdparty.huolala.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaDomainClient;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class HuolalaVoucherWorkerTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ProductMediaStorage files = mock(ProductMediaStorage.class);
    final HuolalaDomainClient domains = mock(HuolalaDomainClient.class);
    final HuolalaVoucherWorker worker =
            new HuolalaVoucherWorker(
                    jdbc,
                    files,
                    domains,
                    Runnable::run,
                    new ProductMediaProperties(),
                    "*.huolala.cn,localhost");

    @Test
    void readyCosObjectRetriesOnlyDomainDeliveryAndKeepsTenantAndVoucherIdentity() {
        String tenant = UUID.randomUUID().toString();
        var record = new HashMap<String, Object>();
        record.putAll(
                Map.of(
                        "id",
                        "V1",
                        "tenant_id",
                        tenant,
                        "connector_id",
                        "C1",
                        "enterprise_id",
                        "E1",
                        "order_no",
                        "3624071813921329726",
                        "line_id",
                        "fee-0",
                        "file_key",
                        tenant + "/integration-huolala-vouchers/C1/O/V1.png",
                        "content_type",
                        "image/png",
                        "file_name",
                        "路桥凭证.png"));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(record));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new IllegalStateException("Order暂不可用"))
                .doNothing()
                .when(domains)
                .voucher(any(), any());
        worker.processOne();
        worker.processOne();
        verifyNoInteractions(files);
        var updates = org.mockito.ArgumentCaptor.forClass(VoucherUpdate.class);
        verify(domains, times(3))
                .voucher(argThat(c -> c.tenantId().toString().equals(tenant)), updates.capture());
        assertThat(
                        updates.getAllValues().stream()
                                .filter(u -> "READY".equals(u.voucher().state()))
                                .toList())
                .extracting(u -> u.voucher().fileKey())
                .containsOnly(record.get("file_key").toString());
        verify(jdbc, atLeastOnce())
                .update(contains("last_error=?"), any(), any(), eq(tenant), eq("V1"), any());
        verify(jdbc).update(contains("state='DONE'"), eq(tenant), eq("V1"), any());
    }

    @Test
    void rejectsNonPlatformAndPrivateUrlsAndVerifiesFileMagicBeforeUpload() {
        for (String url :
                List.of(
                        "http://luna.huolala.cn/a.png",
                        "https://evil.example/a.png",
                        "https://luna.huolala.cn.evil.example/a.png",
                        "https://localhost/a.png",
                        "https://user:password@luna.huolala.cn/a.png"))
            assertThatThrownBy(() -> worker.validateUri(url))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        HuolalaVoucherWorker.contentType(
                                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
                .isEqualTo("image/png");
        assertThat(HuolalaVoucherWorker.contentType("%PDF-1.7".getBytes()))
                .isEqualTo("application/pdf");
        assertThatThrownBy(
                        () -> HuolalaVoucherWorker.contentType("<html>failure</html>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
