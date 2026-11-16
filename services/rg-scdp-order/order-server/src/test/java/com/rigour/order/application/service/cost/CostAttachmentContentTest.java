package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CostAttachmentContentTest {
    @Test
    void acceptsActualOfficePackagesAndRejectsOrdinaryZipsAndWrongCategory() throws Exception {
        String word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        byte[] document = office("word/document.xml");
        assertThatCode(() -> CostAttachmentService.validateEvidence("运输凭证.docx", word, document))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                CostAttachmentService.validateEvidence(
                                        "伪造.docx", word, office("readme.txt")))
                .hasMessageContaining("有效的");
        assertThatThrownBy(() -> CostAttachmentService.evidenceCategory("CUSTOM_URL"))
                .hasMessageContaining("分类");
    }

    private byte[] office(String body) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (var name : java.util.List.of("[Content_Types].xml", body)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write("<xml/>".getBytes());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

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
