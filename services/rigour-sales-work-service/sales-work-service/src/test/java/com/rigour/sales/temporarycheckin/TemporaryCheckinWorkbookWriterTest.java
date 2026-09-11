package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.RULES_VERSION;

import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.*;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskRepository.GroupKey;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskRepository.Member;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 重新打开真实 XLSX 检查证据口径、不同拜访去重、北京时间数值与不执行的公式文本。 */
class TemporaryCheckinWorkbookWriterTest {
    private static final Instant FIRST=Instant.parse("2026-09-07T16:01:02Z");
    private static final Instant LAST=FIRST.plusSeconds(3600);
    private static final UUID FIRST_VISIT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TIED_VISIT=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MATCHED_VISIT=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SALES_A=UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SALES_B=UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID STORE=UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final String SHA="a1".repeat(32),VERSION="b2".repeat(32);

    @Test void fourSheetWorkbookIncludesAuthorizedHistoryAndExactFilteredVisitCounts() throws Exception {
        var prepared=fixture();
        var risk=TemporaryCheckinRiskWorkbookEvidence.from(prepared,List.of(MATCHED_VISIT),"北京");
        try(var book=workbook(risk,List.of(exportRow(MATCHED_VISIT,"SUBMITTED")))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(4);
            assertThat(book.getSheetName(2)).isEqualTo("设备关联");assertThat(book.getSheetName(3)).isEqualTo("录音关联");
            var detail=book.getSheet("打卡明细");
            assertThat(detail.getRow(4).getCell(0).getStringCellValue()).isEqualTo("打卡时间");
            assertThat(detail.getRow(4).getCell(23).getStringCellValue()).isEqualTo("打卡编号");
            assertThat(detail.getRow(4).getCell(24).getStringCellValue()).isEqualTo("浏览器设备编号");
            assertThat(cell(detail,5,"设备打卡次数（本次筛选）").getNumericCellValue()).isEqualTo(1);
            assertThat(cell(detail,5,"设备关联人数（权限内历史）").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(detail,5,"当前风险级别").getStringCellValue()).isEqualTo("高");
            assertThat(cell(detail,5,"当前关联风险线索").getStringCellValue()).contains("多名销售","相同录音文件");
            assertThat(cell(detail,5,"相同录音文件关联情况").getStringCellValue()).contains("3 次拜访（含本次）","其他 2 次","严格更早 2 次");
            var device=book.getSheet("设备关联");var audio=book.getSheet("录音关联");
            assertThat(device.getLastRowNum()).isEqualTo(7);assertThat(audio.getLastRowNum()).isEqualTo(7);
            for(var sheet:List.of(device,audio)) {
                assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("当前管理员授权城市：北京","全部授权历史","本次筛选内");
                assertThat(cell(sheet,5,"本次筛选内").getStringCellValue()).isEqualTo("否");
                assertThat(cell(sheet,6,"本次筛选内").getStringCellValue()).isEqualTo("否");
                assertThat(cell(sheet,7,"本次筛选内").getStringCellValue()).isEqualTo("是");
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)5);
                assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
                assertThat(sheet.isDisplayGridlines()).isFalse();
                for(Cell header:sheet.getRow(4))assertThat(header.getStringCellValue()).matches(".*[\\p{IsHan}].*");
            }
            assertThat(cell(device,5,"该销售使用次数（权限内历史）").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(device,6,"该销售使用次数（权限内历史）").getNumericCellValue()).isEqualTo(1);
            assertThat(cell(device,7,"该销售使用次数（权限内历史）").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(device,5,"首次留存销售（同刻并列）").getStringCellValue()).contains("示例甲","示例乙");
            assertThat(cell(device,5,"最近归属登记销售").getStringCellValue()).isEqualTo("=登记销售");
            assertThat(cell(device,5,"最近归属登记类型").getStringCellValue()).isEqualTo("个人使用登记");
            assertThat(device.getRow(2).getCell(0).getStringCellValue()).contains("不是所有者","适用日期");
            assertThat(audio.getRow(2).getCell(0).getStringCellValue()).contains("首次提交销售不是录制者");
            assertThat(cell(audio,7,"本拜访相同文件片段数").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(audio,7,"关联拜访次数（权限内历史）").getNumericCellValue()).isEqualTo(3);
            assertThat(cell(audio,7,"关联拜访次数（本次筛选）").getNumericCellValue()).isEqualTo(1);
            assertThat(cell(audio,7,"其他拜访次数").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(audio,5,"严格更早拜访次数").getNumericCellValue()).isZero();
            assertThat(cell(audio,6,"严格更早拜访次数").getNumericCellValue()).isZero();
            assertThat(cell(audio,7,"严格更早拜访次数").getNumericCellValue()).isEqualTo(2);
            assertThat(cell(audio,7,"原文件完整摘要（哈希）").getStringCellValue()).isEqualTo(SHA);
            assertThat(cell(audio,7,"原文件大小（字节）").getNumericCellValue()).isEqualTo(98765);
            assertThat(cell(audio,7,"文件时长（秒）").getNumericCellValue()).isEqualTo(61.234);
            assertThat(cell(audio,7,"时长依据").getStringCellValue()).isEqualTo("服务端解析");
            String preview=System.getProperty("qa.workbook.path");
            if(preview!=null&&!preview.isBlank()) {
                try(var output=Files.newOutputStream(Path.of(preview))) {book.write(output);}
            }
        }
    }

    @Test void datesAreBeijingExcelNumbersAndReviewDoesNotHideNewEvidenceOrEvaluateText() throws Exception {
        var risk=TemporaryCheckinRiskWorkbookEvidence.from(fixture(),List.of(MATCHED_VISIT),null);
        try(var book=workbook(risk,List.of(exportRow(MATCHED_VISIT,"SUBMITTED")))) {
            var format=new DataFormatter();var device=book.getSheet("设备关联");var audio=book.getSheet("录音关联");
            assertThat(cell(device,5,"打卡时间").getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(format.formatCellValue(cell(device,5,"打卡时间"))).isEqualTo("2026-09-08 00:01:02");
            assertThat(format.formatCellValue(cell(device,5,"归属登记时间"))).isEqualTo("2026-09-08 01:01:02");
            assertThat(format.formatCellValue(cell(device,5,"登记适用止日"))).isEqualTo("2026-09-01");
            assertThat(cell(device,5,"最近归属登记销售").getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell(device,5,"归属登记备注").getStringCellValue()).isEqualTo("=HYPERLINK(\"https://invalid.test\")");
            assertThat(cell(audio,7,"本拜访原文件名称").getStringCellValue()).contains("=1+1.wav");
            for(var sheet:List.of(device,audio)) {
                assertThat(cell(sheet,5,"当前关联复核状态").getStringCellValue()).isEqualTo("待复核");
                assertThat(cell(sheet,5,"关联证据已变化").getStringCellValue()).isEqualTo("是");
                assertThat(cell(sheet,5,"最近复核结论").getStringCellValue()).isEqualTo("已有说明");
                assertThat(format.formatCellValue(cell(sheet,5,"最近复核时间"))).isEqualTo("2026-09-08 00:01:02");
                assertThat(cell(sheet,5,"最近复核备注").getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell(sheet,5,"最近复核备注").getStringCellValue()).isEqualTo("+SUM(1,2)");
            }
            for(var sheet:book)for(Row row:sheet)for(Cell cell:row)assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
        }
    }

    @Test void emptyOrDraftEvidenceIsUnknownRatherThanZeroAndMissingEvidenceFailsClearly() throws Exception {
        var empty=new TemporaryCheckinRiskService.ExportEvidence(new BatchSummary(List.of(),RULES_VERSION),Map.of(),Map.of(),Map.of(),Map.of());
        var risk=TemporaryCheckinRiskWorkbookEvidence.from(empty,List.of(MATCHED_VISIT),"北京");
        try(var book=workbook(risk,List.of(exportRow(MATCHED_VISIT,"DRAFT")))) {
            assertThat(cell(book.getSheet("打卡明细"),5,"当前风险级别").getCellType()).isEqualTo(CellType.BLANK);
            assertThat(cell(book.getSheet("打卡明细"),5,"相同录音文件关联情况").getStringCellValue()).contains("未纳入");
            assertThat(book.getSheet("设备关联").getRow(5).getCell(0).getStringCellValue()).isEqualTo("当前筛选无记录");
            assertThat(book.getSheet("录音关联").getRow(5).getCell(0).getStringCellValue()).isEqualTo("当前筛选无记录");
        }
        assertThatThrownBy(()->TemporaryCheckinRiskWorkbookEvidence.from(null,List.of(),null)).isInstanceOf(NullPointerException.class);
    }

    private static XSSFWorkbook workbook(TemporaryCheckinRiskWorkbookEvidence risk,List<TemporaryCheckinRepository.ExportRow> rows) throws Exception {
        byte[] bytes=new TemporaryCheckinWorkbookWriter().write(rows,List.of(),Map.of(),Map.of(),"本次筛选：销售甲，风险为高，日期为今日",risk);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }
    private static Cell cell(XSSFSheet sheet,int row,String header) {
        for(Cell cell:sheet.getRow(4))if(header.equals(cell.getStringCellValue()))return sheet.getRow(row).getCell(cell.getColumnIndex());
        throw new AssertionError("缺少表头："+header);
    }
    private static TemporaryCheckinRepository.ExportRow exportRow(UUID id,String status) {
        var row=mock(TemporaryCheckinRepository.ExportRow.class);when(row.id()).thenReturn(id);when(row.status()).thenReturn(status);
        when(row.submittedAt()).thenReturn("DRAFT".equals(status)?null:LAST);when(row.city()).thenReturn("北京");
        when(row.salespersonName()).thenReturn("示例甲");when(row.riskLevel()).thenReturn("LOW");return row;
    }
    private static TemporaryCheckinRiskService.ExportEvidence fixture() {
        var deviceKey=new GroupKey("DEVICE",7);var audioKey=new GroupKey("AUDIO",9);
        var device=summary("DEVICE",7,null);var audio=summary("AUDIO",9,null);var audioContext=summary("AUDIO",9,2L);
        var submission=new SubmissionSummary(MATCHED_VISIT,device,List.of(audioContext),List.of("SHARED_DEVICE","AUDIO_DUPLICATE"),"HIGH",true);
        var assignment=new AssignmentEvent(UUID.randomUUID(),UUID.randomUUID(),"PERSONAL",SALES_A,"=登记销售",
                LocalDate.of(2026,8,1),LocalDate.of(2026,9,1),"=HYPERLINK(\"https://invalid.test\")","示例管理员",LAST,null);
        var review=new ReviewEvent(UUID.randomUUID(),UUID.randomUUID(),"EXPLAINED","+SUM(1,2)","示例管理员",FIRST,"旧版本",RULES_VERSION,2,null);
        var devices=List.of(member("DEVICE",FIRST_VISIT,SALES_A,FIRST,null),member("DEVICE",TIED_VISIT,SALES_B,FIRST,null),member("DEVICE",MATCHED_VISIT,SALES_A,LAST,null));
        var audios=List.of(member("AUDIO",MATCHED_VISIT,SALES_A,LAST,"segment-b"),member("AUDIO",FIRST_VISIT,SALES_A,FIRST,"segment-first"),
                member("AUDIO",MATCHED_VISIT,SALES_A,LAST,"segment-a"),member("AUDIO",TIED_VISIT,SALES_B,FIRST,"segment-tied"));
        return new TemporaryCheckinRiskService.ExportEvidence(new BatchSummary(List.of(submission),RULES_VERSION),Map.of(deviceKey,device,audioKey,audio),
                Map.of(deviceKey,devices,audioKey,audios),Map.of(7L,assignment),Map.of(deviceKey,review,audioKey,review));
    }
    private static GroupSummary summary(String kind,long id,Long earlier) {
        return new GroupSummary(Long.toString(id),TemporaryCheckinRiskRepository.code(kind,id),kind,3,3,2,1,1,FIRST,LAST,"示例甲","PENDING",VERSION,true,FIRST,"示例管理员",
                "AUDIO".equals(kind)?SHA.substring(0,16):null,"AUDIO".equals(kind)?98765L:null,"AUDIO".equals(kind)?61234L:null,"SERVER_PARSED",
                earlier==null?null:2L,earlier,earlier==null?List.of():List.of("segment-a","segment-b"));
    }
    private static Member member(String kind,UUID visit,UUID salesperson,Instant at,String segment) {
        return new Member(kind,"DEVICE".equals(kind)?7:9,visit,at,"北京",salesperson,salesperson.equals(SALES_A)?"示例甲":"示例乙",STORE,"示例门店","设备报告的示例地址",7L,
                "示例浏览器","LOW",segment,segment==null?null:SHA,segment==null?null:98765L,segment==null?null:"=1+1.wav",null,null,null,segment==null?null:61234L,"READY");
    }
}
