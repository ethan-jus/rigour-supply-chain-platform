package com.rigour.order.infrastructure.media;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.infrastructure.config.FundAttachmentAccessProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;

class CosCostAttachmentStorageTest {
    @Test
    void previewReadsPrivateObjectAndBoundsTheReturnedBytes() {
        var cos = org.mockito.Mockito.mock(com.qcloud.cos.COSClient.class);
        var object = new com.qcloud.cos.model.COSObject();
        object.setObjectContent(
                new com.qcloud.cos.model.COSObjectInputStream(
                        new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}),
                        new org.apache.http.client.methods.HttpGet()));
        org.mockito.Mockito.when(cos.getObject("private-bucket", "key")).thenReturn(object);
        var storage = new CosCostAttachmentStorage(cos, "private-bucket");
        assertThat(storage.read("key")).containsExactly(1, 2, 3);
        var large = new com.qcloud.cos.model.COSObject();
        large.setObjectContent(
                new com.qcloud.cos.model.COSObjectInputStream(
                        new java.io.ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]),
                        new org.apache.http.client.methods.HttpGet()));
        org.mockito.Mockito.when(cos.getObject("private-bucket", "large")).thenReturn(large);
        assertThatThrownBy(() -> storage.read("large")).hasMessageContaining("暂时无法读取");
    }

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
        assertThat(
                        CosCostAttachmentStorage.objectMetadata(
                                        "request_new-1", "application/pdf", 10)
                                .getUserMetadata())
                .containsEntry("cost-request-id", "new-1")
                .doesNotContainKey("cost-entry-id");
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
