package com.rigour.sales.temporarycheckin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** 中文业务报表；时间写成Excel日期数值，文本明确写为字符串，避免公式注入和手机号丢零。 */
@Component
class TemporaryCheckinWorkbookWriter {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String[] DAILY={"打卡日期","业务城市","销售姓名","拜访次数","拜访门店数","首次打卡时间","末次打卡时间","待复核次数"};
    private static final String[] DETAILS={"打卡时间","业务城市","销售姓名","门店名称","拜访类型","提交状态","客户姓名","联系电话","沟通记录",
            "实际定位地址","距门店（米）","定位情况","风险级别","复核状态","复核人","复核时间","现场照片数","微信截图",
            "首段录音文件","定位采集时间","经度","纬度","创建时间","打卡编号"};

    byte[] write(List<TemporaryCheckinRepository.ExportRow> records,
            List<TemporaryCheckinStatisticsRepository.DailyAttendance> daily,
            Map<UUID,TemporaryCheckinEvidenceRepository.EvidenceView> evidence,
            Map<UUID,Long> photos,String criteria) {
        try(SXSSFWorkbook workbook=new SXSSFWorkbook(100);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Styles styles=new Styles(workbook);
            Sheet summary=sheet(workbook,"每日销售汇总",DAILY,new int[]{14,14,18,14,16,23,23,16},criteria,
                    "仅统计已提交拜访；无记录不代表旷工。时间统一为北京时间。",styles);
            int index=5;
            for(var row:daily) values(summary.createRow(index++),styles,new Object[]{row.date(),row.city(),row.salespersonName(),
                    row.visitCount(),row.storeCount(),row.firstCheckinAt(),row.lastCheckinAt(),row.pendingReviewCount()});
            finish(summary,index,DAILY.length);
            Sheet detail=sheet(workbook,"打卡明细",DETAILS,new int[]{23,14,18,32,14,14,18,19,52,44,16,20,14,16,18,23,14,28,36,23,17,17,23,40},
                    criteria,"可筛选、排序；录音文件名仅说明已上传文件，不代表已完成服务端解析。",styles);
            index=5;
            for(var row:records) {
                var e=evidence.get(row.id());
                long count=photos.getOrDefault(row.id(),0L);
                if(count==0 && row.storefrontPhotoFilename()!=null && !row.storefrontPhotoFilename().isBlank()) count=1;
                values(detail.createRow(index++),styles,new Object[]{row.submittedAt(),row.city(),row.salespersonName(),row.storeName(),
                        row.visitOrdinal()==null?"未提交":row.visitOrdinal()==1?"首次拜访":"回访",label(row.status()),row.customerName(),row.customerPhone(),row.visitResult(),
                        row.locationAddress(),e==null?null:e.distanceMeters(),e==null?"历史未记录":label(e.locationQuality()),label(row.riskLevel()),
                        e==null?"未记录":label(e.reviewStatus()),e==null?null:e.reviewedBy(),e==null?null:e.reviewedAt(),count,
                        row.wechatScreenshotFilename(),row.audioFilename(),row.locationCapturedAt(),row.longitude(),row.latitude(),row.createdAt(),row.id()});
            }
            finish(detail,index,DETAILS.length);
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch(IOException error) { throw new IllegalStateException("Excel导出失败，请重试",error); }
    }

    private static Sheet sheet(Workbook book,String name,String[] headers,int[] widths,String criteria,String note,Styles s) {
        Sheet sheet=book.createSheet(name);sheet.setDisplayGridlines(false);sheet.setDefaultRowHeightInPoints(25);
        Row title=sheet.createRow(0);title.setHeightInPoints(36);Cell cell=title.createCell(0);cell.setCellValue("销售拜访 · "+name);cell.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0,0,0,headers.length-1));
        for(int r=1;r<=2;r++) {Row row=sheet.createRow(r);row.setHeightInPoints(r==1?48:30);Cell c=row.createCell(0);c.setCellValue(r==1?criteria:note);c.setCellStyle(s.note);sheet.addMergedRegion(new CellRangeAddress(r,r,0,headers.length-1));}
        sheet.createRow(3).setHeightInPoints(8);
        Row header=sheet.createRow(4);header.setHeightInPoints(29);
        for(int i=0;i<headers.length;i++) {Cell c=header.createCell(i);c.setCellValue(headers[i]);c.setCellStyle(s.header);sheet.setColumnWidth(i,widths[i]*256);}
        sheet.createFreezePane(3,5);sheet.setRepeatingRows(new CellRangeAddress(4,4,-1,-1));sheet.setAutobreaks(true);
        sheet.getPrintSetup().setLandscape(true);sheet.getPrintSetup().setFitWidth((short)1);sheet.getPrintSetup().setFitHeight((short)0);
        return sheet;
    }
    private static void finish(Sheet sheet,int end,int columns) {
        sheet.setAutoFilter(new CellRangeAddress(4,Math.max(4,end-1),0,columns-1));
        if(end==5) {Row row=sheet.createRow(5);row.createCell(0).setCellValue("当前筛选无记录");}
    }
    private static void values(Row row,Styles styles,Object[] values) {
        int lines=2;
        for(int i=0;i<values.length;i++) if(values[i] instanceof String text) {
            int visibleWidth=Math.max(8,row.getSheet().getColumnWidth(i)/256);
            int visualLength=text.codePoints().map(c->c>255?2:1).sum();
            lines=Math.max(lines,Math.max((visualLength+visibleWidth-1)/visibleWidth,text.split("\\n",-1).length));
        }
        row.setHeightInPoints(Math.min(409,lines*15+8));
        int stripe=(row.getRowNum()-5)%2;
        for(int i=0;i<values.length;i++) {
            Cell cell=row.createCell(i);Object value=values[i];cell.setCellStyle(styles.body[stripe]);
            if(value instanceof Instant time) {cell.setCellValue(time.atZone(ZONE).toLocalDateTime());cell.setCellStyle(styles.time[stripe]);}
            else if(value instanceof LocalDate date) {cell.setCellValue(date.atStartOfDay());cell.setCellStyle(styles.date[stripe]);}
            else if(value instanceof Number number) {cell.setCellValue(number.doubleValue());cell.setCellStyle(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long ? styles.integer[stripe] : styles.number[stripe]);}
            else if(value!=null) {String text=String.valueOf(value);cell.setCellValue(text.length()>32767?text.substring(0,32764)+"…":text);}
        }
    }
    static String label(String value) {
        if(value==null) return "未记录";
        return switch(value) {
            case "SUBMITTED" -> "已提交"; case "DRAFT" -> "草稿";
            case "GOOD" -> "定位正常";case "LOW_ACCURACY" -> "定位精度偏低";case "STALE" -> "定位较早";
            case "TIME_UNKNOWN" -> "定位时间未知";case "MISSING" -> "未取得定位";case "USER_REPORTED" -> "已报告位置不准";
            case "OUT_OF_RANGE" -> "超出距离提醒";case "STORE_UNLOCATED" -> "门店位置未核验";case "LEGACY" -> "历史记录";
            case "PENDING" -> "待复核";case "APPROVED" -> "已通过";case "FOLLOW_UP" -> "待跟进";case "FLAGGED" -> "已标记异常";
            case "NONE" -> "无";case "LOW" -> "低";case "MEDIUM" -> "中";case "HIGH" -> "高";
            default -> value;
        };
    }
    private static final class Styles {
        final CellStyle title,header,note;
        final CellStyle[] body=new CellStyle[2],time=new CellStyle[2],date=new CellStyle[2],number=new CellStyle[2],integer=new CellStyle[2];
        Styles(Workbook book) {
            Font normal=book.createFont();normal.setFontName("微软雅黑");normal.setFontHeightInPoints((short)11);
            Font white=book.createFont();white.setFontName("微软雅黑");white.setFontHeightInPoints((short)11);white.setBold(true);white.setColor(IndexedColors.WHITE.getIndex());
            Font large=book.createFont();large.setFontName("微软雅黑");large.setFontHeightInPoints((short)18);large.setBold(true);large.setColor(IndexedColors.WHITE.getIndex());
            title=book.createCellStyle();title.setFont(large);title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());title.setFillPattern(FillPatternType.SOLID_FOREGROUND);title.setVerticalAlignment(VerticalAlignment.CENTER);
            header=book.createCellStyle();header.cloneStyleFrom(title);header.setFont(white);header.setAlignment(HorizontalAlignment.LEFT);
            note=book.createCellStyle();note.setFont(normal);note.setWrapText(true);note.setVerticalAlignment(VerticalAlignment.CENTER);
            for(int i=0;i<2;i++) {
                body[i]=book.createCellStyle();body[i].setFont(normal);body[i].setVerticalAlignment(VerticalAlignment.CENTER);body[i].setWrapText(true);body[i].setIndention((short)1);
                body[i].setFillForegroundColor((i==0?IndexedColors.WHITE:IndexedColors.LIGHT_CORNFLOWER_BLUE).getIndex());body[i].setFillPattern(FillPatternType.SOLID_FOREGROUND);
                body[i].setBorderBottom(BorderStyle.HAIR);body[i].setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
                time[i]=book.createCellStyle();time[i].cloneStyleFrom(body[i]);time[i].setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
                date[i]=book.createCellStyle();date[i].cloneStyleFrom(body[i]);date[i].setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
                integer[i]=book.createCellStyle();integer[i].cloneStyleFrom(body[i]);integer[i].setDataFormat(book.createDataFormat().getFormat("0"));
                number[i]=book.createCellStyle();number[i].cloneStyleFrom(body[i]);number[i].setDataFormat(book.createDataFormat().getFormat("0.00######"));
            }
        }
    }
}
