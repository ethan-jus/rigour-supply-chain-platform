package com.rigour.order.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.infrastructure.config.FundAttachmentAccessProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;

class CosCostAttachmentStorageTest {
    @Test
    void sourceDirectoryAndMetadataIdentifyOwningServiceAndCostEntry() {
        String key = CosCostAttachmentStorage.objectKey("tenant-a", "entry-1", "image/jpeg");
        assertThat(key).startsWith("tenant-a/order-cost-attachments/entry-1/").endsWith(".jpg");
        var metadata = CosCostAttachmentStorage.objectMetadata("entry-1", "image/jpeg", 100);
        assertThat(metadata.getUserMetadata())
                .containsEntry("source-system", "ORDER")
                .containsEntry("business-type", "cost-attachment")
                .containsEntry("cost-entry-id", "entry-1");
        assertThat(metadata.getContentLength()).isEqualTo(100);
        assertThat(metadata.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void missingStorageConfigurationIsActionableAndMakesNoNetworkRequest() {
        var properties = new FundAttachmentAccessProperties();
        properties.getCos().setEnabled(true);
        var storage = new CosCostAttachmentStorage(properties);
        assertThatThrownBy(
                        () -> storage.upload("tenant-a", "entry-1", "image/jpeg", new byte[] {1}))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(ErrorCode.COST_ATTACHMENT_STORAGE_UNAVAILABLE))
                .hasMessageContaining("请联系管理员");
    }

    @Test
    void downloadIsSignedAsAttachmentWithUtf8FileNameRatherThanAPlatformLink() throws Exception {
        var cos = org.mockito.Mockito.mock(com.qcloud.cos.COSClient.class);
        org.mockito.Mockito.when(
                        cos.generatePresignedUrl(
                                org.mockito.ArgumentMatchers.any(
                                        com.qcloud.cos.model.GeneratePresignedUrlRequest.class)))
                .thenReturn(
                        java.net
                                .URI
                                .create("https://our-bucket.cos.example/signed-download")
                                .toURL());
        var storage = new CosCostAttachmentStorage(cos, "private-bucket");
        String key = "tenant/integration-huolala-vouchers/C/O/P1.png";
        assertThat(storage.downloadUrl(key, "回单 凭证.png"))
                .isEqualTo("https://our-bucket.cos.example/signed-download");
        var capture =
                org.mockito.ArgumentCaptor.forClass(
                        com.qcloud.cos.model.GeneratePresignedUrlRequest.class);
        org.mockito.Mockito.verify(cos).generatePresignedUrl(capture.capture());
        assertThat(capture.getValue().getKey()).isEqualTo(key);
        assertThat(capture.getValue().getResponseHeaders().getContentDisposition())
                .startsWith("attachment;")
                .contains("filename*=UTF-8''%E5%9B%9E%E5%8D%95%20")
                .doesNotContain("\r", "\n");
    }
}
