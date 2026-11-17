package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

class TransportFeeWorkbookReaderTest {
    static byte[] workbook(String rows) {
        try {
            var out = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(out)) {
                for (var e :
                        Map.of(
                                        "[Content_Types].xml",
                                        "<Types/>",
                                        "xl/workbook.xml",
                                        "<workbook"
                                            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                                            + " name=\"运费补录\" sheetId=\"1\""
                                            + " r:id=\"rId1\"/></sheets></workbook>",
                                        "xl/_rels/workbook.xml.rels",
                                        "<Relationships><Relationship Id=\"rId1\""
                                            + " Target=\"worksheets/sheet1.xml\"/></Relationships>",
                                        "xl/worksheets/sheet1.xml",
                                        "<worksheet><sheetData><row r=\"1\">"
                                                + cell("A1", "快递单号")
                                                + cell("B1", "运费（元）")
                                                + cell("C1", "备注")
                                                + "</row>"
                                                + rows
                                                + "</sheetData></worksheet>")
                                .entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String cell(String ref, String value) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + value + "</t></is></c>";
    }

    static String row(int line, String no, String fee, String remark) {
        return "<row r=\""
                + line
                + "\">"
                + cell("A" + line, no)
                + cell("B" + line, fee)
                + cell("C" + line, remark)
                + "</row>";
    }

    @Test
    void preservesLongTextAndLeadingZerosAndExactMoney() {
        var r =
                TransportFeeWorkbookReader.read(
                                workbook(row(2, "00003627726570632356867", "10.01", "备注")))
                        .getFirst();
        assertThat(r.trackingNo()).isEqualTo("00003627726570632356867");
        assertThat(r.amount()).isEqualByComparingTo("10.01");
        assertThat(r.problem()).isNull();
    }

    @Test
    void numericLongNumbersFormulasAndInvalidPrecisionAreExplicitFailures() {
        String number =
                "<row r=\"2\"><c r=\"A2\"><v>3627726570632356867</v></c>"
                        + cell("B2", "3")
                        + "</row>";
        String formula =
                "<row r=\"3\">"
                        + cell("A3", "SF001")
                        + "<c r=\"B3\"><f>SUM(1,2)</f><v>3</v></c></row>";
        var rows =
                TransportFeeWorkbookReader.read(
                        workbook(
                                number
                                        + formula
                                        + row(4, "SF002", "1.001", "")
                                        + row(5, "SF003", "-1", "")
                                        + row(6, "SF004", "0", "")));
        assertThat(rows).allSatisfy(r -> assertThat(r.problem()).isNotBlank());
        assertThat(rows.get(0).problem()).contains("长单号");
        assertThat(rows.get(1).problem()).contains("公式");
        assertThat(rows.get(4).problem()).contains("零元");
    }

    @Test
    void zeroWithReasonAndShortNumericTrackingAreAllowed() {
        var rows =
                TransportFeeWorkbookReader.read(
                        workbook(
                                row(2, "SF001", "0", "免运费")
                                        + "<row r=\"3\"><c r=\"A3\"><v>12345</v></c>"
                                        + cell("B3", "3.50")
                                        + "</row>"));
        assertThat(rows).allSatisfy(r -> assertThat(r.problem()).isNull());
    }

    @Test
    void perRecordEvidenceContainsOnlyItsRowAndIsReadableXlsx() {
        var row =
                new TransportFeeWorkbookReader.Row(
                        6, "000SF006", new java.math.BigDecimal("1.23"), "折扣 & 附加费 <核对>", null);
        var bytes = TransportFeeWorkbookReader.evidence(row, "原始账单.xlsx");
        var read = TransportFeeWorkbookReader.read(bytes);
        assertThat(read).hasSize(1);
        assertThat(read.getFirst().trackingNo()).isEqualTo("000SF006");
        assertThat(read.getFirst().remark()).isEqualTo(row.remark());
        assertThat(read.getFirst().amount()).isEqualByComparingTo("1.23");
        CostAttachmentService.validateEvidence("本笔运费.xlsx", TransportFeeImportService.MIME, bytes);
    }

    @Test
    void refusesOversizedRowRangeAndMissingWorkbook() {
        assertThatThrownBy(
                        () -> TransportFeeWorkbookReader.read(workbook(row(502, "SF001", "1", ""))))
                .hasMessageContaining("500");
        assertThatThrownBy(() -> TransportFeeWorkbookReader.read(new byte[] {1, 2, 3}))
                .hasMessageContaining("缺少");
    }
}
