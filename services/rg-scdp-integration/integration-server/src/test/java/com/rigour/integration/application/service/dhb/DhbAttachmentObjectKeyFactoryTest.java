package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DhbAttachmentObjectKeyFactoryTest {

    @Test
    void generatesStableFundAttachmentKeyFromSourceDocumentAndContent() {
        DhbAttachmentObjectKeyFactory factory = new DhbAttachmentObjectKeyFactory("fund-attachments");

        String first = factory.generate("tenant-1", "FR.20260826.0247",
                "/files/202608260239531787726393103.png?token=secret",
                new byte[]{1, 2, 3}, "202608260239531787726393103.png", "image/png");
        String second = factory.generate("tenant-1", "FR.20260826.0247",
                "/files/202608260239531787726393103.png?token=secret",
                new byte[]{1, 2, 3}, "202608260239531787726393103.png", "image/png");

        assertThat(first).isEqualTo(second)
                .startsWith("tenant-1/fund-attachments/FR_20260826_0247/")
                .endsWith(".png");
    }

    @Test
    void derivesPdfExtensionFromContentTypeWhenFileNameIsMissing() {
        DhbAttachmentObjectKeyFactory factory = new DhbAttachmentObjectKeyFactory("fund-attachments");

        String key = factory.generate("tenant-1", "FP.20260826.0001",
                "payment-proof", new byte[]{4, 5, 6}, null, "application/pdf");

        assertThat(key).endsWith(".pdf");
    }

    @Test
    void rejectsUnsafeObjectPrefix() {
        assertThatThrownBy(() -> new DhbAttachmentObjectKeyFactory("../fund-attachments"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("资金附件 COS object-prefix 必须是安全的相对路径");
    }
}
