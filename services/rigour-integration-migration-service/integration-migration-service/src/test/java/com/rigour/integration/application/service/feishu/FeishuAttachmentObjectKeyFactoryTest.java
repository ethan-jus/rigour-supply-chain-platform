package com.rigour.integration.application.service.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeishuAttachmentObjectKeyFactoryTest {

    @Test
    void generatesStableKeySeparatedByTableSourceDocumentAndField() {
        FeishuAttachmentObjectKeyFactory factory = new FeishuAttachmentObjectKeyFactory("feishu-attachments");

        String key = factory.generate("tenant-1", "FEISHU_SALES_ORDER", "XS.20260901.0001",
                "回款凭证", new byte[]{1, 2, 3}, "receipt.png", "image/png");

        assertThat(key)
                .startsWith("tenant-1/feishu-attachments/FEISHU_SALES_ORDER/XS_20260901_0001/")
                .contains("/回款凭证/")
                .endsWith(".png");
    }

    @Test
    void infersImageSuffixFromContentWhenFeishuOmitsNameAndContentType() {
        FeishuAttachmentObjectKeyFactory factory = new FeishuAttachmentObjectKeyFactory("feishu-attachments");

        String key = factory.generate("tenant-1", "FEISHU_SALES_ORDER", "DD202608303357",
                "回款凭证", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}, null, null);

        assertThat(key)
                .startsWith("tenant-1/feishu-attachments/FEISHU_SALES_ORDER/DD202608303357/")
                .contains("/回款凭证/")
                .endsWith(".jpg");
    }

    @Test
    void rejectsUnsafePrefix() {
        assertThatThrownBy(() -> new FeishuAttachmentObjectKeyFactory("../feishu"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("飞书附件 COS object-prefix 必须是安全的相对路径");
    }
}
