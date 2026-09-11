package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 对实际ZIP/XLSX重新读取，验证业务日期、文本安全和格式，避免只断言生成器实现。 */
class TemporaryCheckinWorkbookTest {
    @Test void realWorkbookHasChineseHeadersBeijingDatesAndReadableTypes() throws Exception {
        UUID id=UUID.randomUUID(),sales=UUID.randomUUID();
        Instant at=Instant.parse("2026-09-07T16:33:33Z");
        var row=mock(TemporaryCheckinRepository.ExportRow.class);
        when(row.id()).thenReturn(id);when(row.city()).thenReturn("北京");when(row.salespersonName()).thenReturn("张销售");
        when(row.storeName()).thenReturn("嘉邻便利店（示例）");when(row.submittedAt()).thenReturn(at);
        when(row.createdAt()).thenReturn(at.minusSeconds(180));when(row.locationCapturedAt()).thenReturn(at.minusSeconds(1));
        when(row.customerPhone()).thenReturn("0013800123456");when(row.customerName()).thenReturn("=1+1");
        when(row.visitResult()).thenReturn("已向店长介绍新品，约定下周回访。\n需补充两箱纸巾。");
        when(row.status()).thenReturn("SUBMITTED");when(row.visitOrdinal()).thenReturn(2L);when(row.riskLevel()).thenReturn("LOW");
        when(row.locationAddress()).thenReturn("北京市朝阳区嘉多丽园南区");when(row.audioFilename()).thenReturn("现场录音.m4a");
        var daily=new TemporaryCheckinStatisticsRepository.DailyAttendance(LocalDate.of(2026,9,8),"北京",sales,"张销售",3,2,at,at.plusSeconds(1800),1);
        var e=new TemporaryCheckinEvidenceRepository.EvidenceView("GOOD",null,null,null,null,null,new java.math.BigDecimal("36.8"),"APPROVED","管理员",at.plusSeconds(120),null);
        byte[] bytes=new TemporaryCheckinWorkbookWriter().write(List.of(row,row),List.of(daily),Map.of(id,e),Map.of(id,2L),"日期：2026-09-08 至 2026-09-08  ｜  城市：北京  ｜  状态：已提交");
        assertThat(bytes[0]).isEqualTo((byte)'P');assertThat(bytes[1]).isEqualTo((byte)'K');
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(book.getSheetName(0)).isEqualTo("每日销售汇总");assertThat(book.getSheetName(1)).isEqualTo("打卡明细");
            var summary=book.getSheetAt(0);var detail=book.getSheetAt(1);var format=new DataFormatter();
            for(var sheet:List.of(summary,detail)) {
                for(var cell:sheet.getRow(4)) assertThat(cell.getStringCellValue()).matches(".*[\\p{IsHan}].*").doesNotContain("_");
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)5);
                assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
                assertThat(sheet.isDisplayGridlines()).isFalse();
            }
            assertThat(format.formatCellValue(summary.getRow(5).getCell(0))).isEqualTo("2026-09-08");
            assertThat(summary.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(3);
            assertThat(detail.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(format.formatCellValue(detail.getRow(5).getCell(0))).isEqualTo("2026-09-08 00:33:33");
            assertThat(format.formatCellValue(detail.getRow(5).getCell(15))).isEqualTo("2026-09-08 00:35:33");
            assertThat(detail.getRow(5).getCell(6).getCellType()).isEqualTo(CellType.STRING);
            assertThat(detail.getRow(5).getCell(6).getStringCellValue()).isEqualTo("=1+1");
            assertThat(detail.getRow(5).getCell(7).getStringCellValue()).isEqualTo("0013800123456");
            assertThat(detail.getRow(5).getCell(16).getNumericCellValue()).isEqualTo(2);
            assertThat(detail.getRow(5).getCell(0).getCellStyle().getFillForegroundColor()).isNotEqualTo(detail.getRow(6).getCell(0).getCellStyle().getFillForegroundColor());
            assertThat(detail.getColumnWidth(0)).isGreaterThanOrEqualTo(23*256);
        }
        String preview=System.getProperty("checkin.workbook.preview");
        if(preview!=null) Files.write(Path.of(preview),bytes);
    }

    @Test void emptyWorkbookKeepsHeadersAndNoFakeTotals() throws Exception {
        byte[] bytes=new TemporaryCheckinWorkbookWriter().write(List.of(),List.of(),Map.of(),Map.of(),"当前筛选无匹配记录");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for(int i=0;i<2;i++) assertThat(book.getSheetAt(i).getRow(5).getCell(0).getStringCellValue()).isEqualTo("当前筛选无记录");
        }
    }
}
