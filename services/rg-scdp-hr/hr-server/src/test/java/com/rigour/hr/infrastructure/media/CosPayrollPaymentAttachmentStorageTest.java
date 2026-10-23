package com.rigour.hr.infrastructure.media;

import static org.assertj.core.api.Assertions.*;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class CosPayrollPaymentAttachmentStorageTest {
    @Test void privateObjectNamespaceCannotBeConfusedWithExpenseAttachments() {
        String tenant=UUID.randomUUID().toString(), payroll=UUID.randomUUID().toString(),payment=UUID.randomUUID().toString();
        for(var type:java.util.Map.of("image/png","png","image/jpeg","jpg","application/pdf","pdf").entrySet()) {
            String key=CosPayrollPaymentAttachmentStorage.objectKey(tenant,payroll,payment,type.getKey());
            assertThat(key).startsWith(tenant+"/hr-payroll-payment-attachments/"+payroll+"/"+payment+"/").endsWith("."+type.getValue());
            assertThat(key).isNotEqualTo(CosPayrollPaymentAttachmentStorage.objectKey(tenant,payroll,payment,type.getKey()));
        }
        assertThatThrownBy(() -> CosPayrollPaymentAttachmentStorage.objectKey("../other",payroll,payment,"image/png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CosPayrollPaymentAttachmentStorage.objectKey(tenant,payroll,payment,"image/svg+xml")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingSecretsDoNotCreatePublicOrLocalFallback() {
        var storage=new CosPayrollPaymentAttachmentStorage("region","bucket","","","");
        assertThatThrownBy(() -> storage.read("anything")).isInstanceOf(BusinessException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> storage.upload("tenant","payroll","payment","image/png",new byte[8])).isInstanceOf(BusinessException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> storage.signedDownload("anything","回单.pdf","application/pdf",java.time.Instant.now().plusSeconds(60))).hasMessageContaining("未配置");
        storage.discard("anything");
        storage.close();
    }
    @Test void signedLinkIsHttpsReadOnlySevenDaysWithOriginalFileNameAndNoPublicAclChange() {
        var storage = new CosPayrollPaymentAttachmentStorage("ap-beijing","test-1234567890","AKIDEXAMPLE","example-secret","");
        try {
            String key=CosPayrollPaymentAttachmentStorage.objectKey(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"application/pdf");
            var expiration=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(java.time.Duration.ofDays(7));
            var uri=java.net.URI.create(storage.signedDownload(key,"工资回单.pdf","application/pdf",expiration));
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("test-1234567890.cos.ap-beijing.myqcloud.com");
            var query=java.net.URLDecoder.decode(uri.getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(query).contains("q-signature=", "q-header-list=host", "response-content-disposition=");
            assertThat(java.net.URLDecoder.decode(query,java.nio.charset.StandardCharsets.UTF_8))
                    .contains(";"+expiration.getEpochSecond()).contains("private, no-store");
            assertThatThrownBy(() -> storage.signedDownload("other/key","回单.pdf","application/pdf",expiration)).hasMessageContaining("地址无效");
            assertThatThrownBy(() -> storage.signedDownload(key,"回单.pdf","application/pdf",java.time.Instant.EPOCH)).hasMessageContaining("地址无效");
        } finally { storage.close(); }
    }
}
