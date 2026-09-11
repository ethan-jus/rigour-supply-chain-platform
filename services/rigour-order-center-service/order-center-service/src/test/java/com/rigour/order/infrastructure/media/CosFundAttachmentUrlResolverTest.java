package com.rigour.order.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rigour.order.infrastructure.config.FundAttachmentAccessProperties;
import org.junit.jupiter.api.Test;

class CosFundAttachmentUrlResolverTest {

    @Test
    void rejectsNonPositiveCosTimeouts() {
        FundAttachmentAccessProperties properties = validProperties();
        properties.getCos().setSocketTimeoutMs(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CosFundAttachmentUrlResolver(properties));

        assertEquals("资金附件 COS 连接和读取超时必须大于0", error.getMessage());
    }

    @Test
    void rejectsObjectKeyOutsideFundAttachmentDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> CosFundAttachmentUrlResolver.validateKey("tenant-id",
                        "tenant-id/product-images/P-1/hash.jpg", "fund-attachments"));
    }

    @Test
    void acceptsConfiguredFeishuAttachmentDirectory() {
        CosFundAttachmentUrlResolver.validateKey("tenant-id",
                "tenant-id/feishu-attachments/FEISHU_SALES_ORDER/DD202609010001/回款凭证/hash.png",
                java.util.List.of("fund-attachments", "feishu-attachments"));
    }

    @Test
    void usesInlinePreviewHeadersForKnownAttachmentTypes() {
        assertEquals("inline",
                CosFundAttachmentUrlResolver.previewHeaders("tenant-id/feishu-attachments/order/proof.JPEG")
                        .getContentDisposition());
        assertEquals("image/jpeg",
                CosFundAttachmentUrlResolver.previewContentType(
                        "tenant-id/feishu-attachments/order/proof.JPEG"));
        assertEquals("application/pdf",
                CosFundAttachmentUrlResolver.previewContentType(
                        "tenant-id/feishu-attachments/order/proof.pdf"));
    }

    private static FundAttachmentAccessProperties validProperties() {
        FundAttachmentAccessProperties properties = new FundAttachmentAccessProperties();
        FundAttachmentAccessProperties.Cos cos = properties.getCos();
        cos.setEnabled(true);
        cos.setObjectPrefix("fund-attachments");
        cos.setRegion("ap-test");
        cos.setBucket("rigour-order-test-1250000000");
        cos.setSecretId("test-secret-id");
        cos.setSecretKey("test-secret-key");
        return properties;
    }
}
