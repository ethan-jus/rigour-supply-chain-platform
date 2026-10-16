package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CostAttachmentContentTest {
    @Test
    void declaredTypeMustMatchContent() {
        assertThat(
                        CostAttachmentService.validContent(
                                "application/pdf", "%PDF-1.7 test".getBytes()))
                .isTrue();
        assertThat(CostAttachmentService.validContent("image/png", "%PDF-1.7 test".getBytes()))
                .isFalse();
        assertThat(CostAttachmentService.validContent("image/svg+xml", "<svg></svg>".getBytes()))
                .isFalse();
        assertThat(CostAttachmentService.validContent("image/png", new byte[0])).isFalse();
    }
}
