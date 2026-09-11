package com.rigour.sales.temporarycheckin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** 中文业务报表；时间写成Excel日期数值，文本明确写为字符串，避免公式注入和手机号丢零。 */
@Component
class TemporaryCheckinWorkbookWriter {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String[] DAILY={"打卡日期","业务城市","销售姓名","拜访次数","拜访门店数","录音数量","首次打卡时间","末次打卡时间","待复核次数"};
    private static final String[] DETAILS={"打卡时间","业务城市","销售姓名","门店名称","拜访类型","提交状态","客户姓名","联系电话","沟通记录",
            "实际定位地址","距门店（米）","定位情况","风险级别","复核状态","复核人","复核时间","现场照片数","微信截图",
            "首段录音文件","定位采集时间","经度","纬度","创建时间","打卡编号"};
    private static final String[] RISK_DETAILS={"浏览器设备编号","设备关联人数（权限内历史）","设备打卡次数（权限内历史）","设备打卡次数（本次筛选）",
            "相同录音文件关联情况","当前风险级别","当前关联风险线索","当前关联待复核情况","设备关联复核状态","录音关联复核状态","关联证据已变化","风控规则版本"};
    private static final String[] DEVICE={"浏览器设备编号","本次筛选内","打卡时间","业务城市","销售姓名","销售编号","门店名称","打卡编号","实际定位地址",
            "该销售使用次数（权限内历史）","设备关联人数（权限内历史）","设备打卡次数（权限内历史）","设备打卡次数（本次筛选）",
            "首次留存销售（同刻并列）","首次留存打卡时间","末次留存打卡时间","最近归属登记类型","最近归属登记销售","登记销售编号",
            "登记适用起日","登记适用止日","归属登记时间","归属登记人","归属登记范围","归属登记备注","当前关联复核状态","关联证据已变化",
            "最近复核结论","最近复核人","最近复核时间","最近复核备注","当前证据版本"};
    private static final String[] AUDIO={"录音文件编号","原文件完整摘要（哈希）","原文件大小（字节）","文件时长（秒）","时长依据","本次筛选内","打卡时间","业务城市",
            "销售姓名","销售编号","门店名称","浏览器设备编号","打卡编号","本拜访原文件名称","本拜访相同文件片段数","本拜访片段编号",
            "关联拜访次数（权限内历史）","关联拜访次数（本次筛选）","其他拜访次数","严格更早拜访次数","关联销售人数（权限内历史）","关联日期数（北京时间）",
            "首次提交销售（同刻并列）","首次关联打卡时间","末次关联打卡时间","当前关联复核状态","关联证据已变化","最近复核结论","最近复核人",
            "最近复核时间","最近复核备注","当前证据版本"};

    byte[] write(List<TemporaryCheckinRepository.ExportRow> records,
            List<TemporaryCheckinStatisticsRepository.DailyAttendance> daily,
            Map<UUID,TemporaryCheckinEvidenceRepository.EvidenceView> evidence,
            Map<UUID,Long> photos,String criteria) {
        return write(records,daily,evidence,photos,criteria,null);
    }

    byte[] write(List<TemporaryCheckinRepository.ExportRow> records,
            List<TemporaryCheckinStatisticsRepository.DailyAttendance> daily,
            Map<UUID,TemporaryCheckinEvidenceRepository.EvidenceView> evidence,
            Map<UUID,Long> photos,String criteria,TemporaryCheckinRiskWorkbookEvidence risk) {
        try(SXSSFWorkbook workbook=new SXSSFWorkbook(100);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Styles styles=new Styles(workbook);
            Sheet summary=sheet(workbook,"每日销售汇总",DAILY,new int[]{14,14,18,14,16,16,23,23,16},criteria,
                    "仅统计已提交拜访；录音数量指至少有一段有效、已上传且未删除录音的拜访次数，同次拜访多段计一次。无记录不代表旷工；时间为北京时间。",styles);
            int index=5;
            for(var row:daily) values(summary.createRow(index++),styles,new Object[]{row.date(),row.city(),row.salespersonName(),
                    row.visitCount(),row.storeCount(),row.audioCount(),row.firstCheckinAt(),row.lastCheckinAt(),row.pendingReviewCount()});
            finish(summary,index,DAILY.length);
            var headers=risk==null?DETAILS:append(DETAILS,RISK_DETAILS);
            var widths=new int[]{23,14,18,32,14,14,18,19,52,44,16,20,14,16,18,23,14,28,36,23,17,17,23,40};
            if(risk!=null)widths=append(widths,new int[]{22,24,24,24,58,16,38,24,22,42,20,24});
            Sheet detail=sheet(workbook,"打卡明细",headers,widths,
                    criteria,risk==null?"可筛选、排序；录音文件名仅说明已上传文件，不代表已完成服务端解析。":
                    "原风险级别为提交时留存值；追加列为当前授权历史关联判断。设备编号不是硬件编号，同文件不等于同录制者。",styles);
            Map<String,Long> deviceMatches=risk==null?Map.of():risk.devices().stream().collect(Collectors.toMap(g->g.summary().id(),TemporaryCheckinRiskWorkbookEvidence.Group::matchedCount));
            index=5;
            for(var row:records) {
                var e=evidence.get(row.id());
                long count=photos.getOrDefault(row.id(),0L);
                if(count==0 && row.storefrontPhotoFilename()!=null && !row.storefrontPhotoFilename().isBlank()) count=1;
                Object[] cells=new Object[]{row.submittedAt(),row.city(),row.salespersonName(),row.storeName(),
                        row.visitOrdinal()==null?"未提交":row.visitOrdinal()==1?"首次拜访":"回访",label(row.status()),row.customerName(),row.customerPhone(),row.visitResult(),
                        row.locationAddress(),e==null?null:e.distanceMeters(),e==null?"历史未记录":label(e.locationQuality()),label(row.riskLevel()),
                        e==null?"未记录":label(e.reviewStatus()),e==null?null:e.reviewedBy(),e==null?null:e.reviewedAt(),count,
                        row.wechatScreenshotFilename(),row.audioFilename(),row.locationCapturedAt(),row.longitude(),row.latitude(),row.createdAt(),row.id()};
                if(risk!=null)cells=append(cells,riskCells(risk.submissions().get(row.id()),deviceMatches,risk.rulesVersion()));
                values(detail.createRow(index++),styles,cells);
            }
            finish(detail,index,headers.length);
            if(risk!=null)writeAssociations(workbook,styles,criteria,risk);
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch(IOException error) { throw new IllegalStateException("Excel导出失败，请重试",error); }
    }

    private static Object[] riskCells(TemporaryCheckinRiskModels.SubmissionSummary item,Map<String,Long> deviceMatches,String rulesVersion) {
        if(item==null)return new Object[]{"未留存可用关联",null,null,null,"未纳入已提交有效拜访关联",null,"未计算","未计算","未计算","未计算",null,rulesVersion};
        var device=item.device();
        String files=item.audios().isEmpty()?"未留存可关联录音":item.audios().stream().map(a->a.code()+"："+a.historyCount()
                +" 次拜访（含本次），其他 "+a.otherCount()+" 次，严格更早 "+a.earlierCount()+" 次").collect(Collectors.joining("\n"));
        String audioReview=item.audios().stream().map(a->a.code()+"："+label(a.reviewStatus())).collect(Collectors.joining("\n"));
        boolean changed=(device!=null&&device.newEvidence())||item.audios().stream().anyMatch(TemporaryCheckinRiskModels.GroupSummary::newEvidence);
        return new Object[]{device==null?"未留存":device.code(),device==null?null:device.salespersonCount(),device==null?null:device.historyCount(),
                device==null?null:deviceMatches.get(device.id()),files,label(item.riskLevel()),
                item.riskReasons().isEmpty()?"未发现设备或同文件关联线索":item.riskReasons().stream().map(TemporaryCheckinWorkbookWriter::label).collect(Collectors.joining("、")),
                item.reviewPending()?"有待复核关联":"无待复核关联",device==null?"未留存":label(device.reviewStatus()),
                audioReview.isEmpty()?"无可关联录音":audioReview,changed?"是":"否",rulesVersion};
    }

    private static void writeAssociations(Workbook book,Styles styles,String criteria,TemporaryCheckinRiskWorkbookEvidence risk) {
        String history="授权历史："+risk.scopeLabel()+"；仅现存已提交拜访。筛选条件用于选定关联组，表内保留该组全部授权历史；“本次筛选内”精确对应打卡明细编号。";
        Sheet devices=sheet(book,"设备关联",DEVICE,new int[]{22,16,23,14,18,40,32,40,44,26,26,26,26,28,23,23,22,22,40,16,16,23,18,20,44,22,20,22,18,23,44,68},
                criteria+"\n"+history,"一行是同设备的一次不同拜访。浏览器编号不等于硬件或操作者；首次留存销售不是所有者。最近归属是管理员登记，需结合适用日期判断。",styles);
        int index=5;
        for(var group:risk.devices()) {
            var summary=group.summary();var assignment=group.assignment();var review=group.review();
            String firstSales=firstSales(group);
            for(var visit:group.visits()) {
                var m=visit.member();
                values(devices.createRow(index++),styles,new Object[]{summary.code(),yes(visit.matchesFilter()),m.submittedAt(),m.city(),m.salespersonName(),m.salespersonId(),
                        m.storeName(),m.submissionId(),m.locationAddress(),visit.salespersonHistoryCount(),summary.salespersonCount(),summary.historyCount(),group.matchedCount(),
                        firstSales,summary.firstSubmittedAt(),summary.lastSubmittedAt(),assignment==null?"未登记":label(assignment.assignmentType()),
                        assignment==null?null:assignment.salespersonName(),assignment==null?null:assignment.salespersonId(),assignment==null?null:assignment.validFrom(),
                        assignment==null?null:assignment.validTo(),assignment==null?null:assignment.assignedAt(),assignment==null?null:assignment.actor(),
                        assignment==null?null:assignment.scopeCity()==null?"总部范围":assignment.scopeCity(),assignment==null?null:assignment.note(),
                        label(summary.reviewStatus()),yes(summary.newEvidence()),review==null?"未复核":label(review.status()),review==null?null:review.actor(),
                        review==null?null:review.reviewedAt(),review==null?null:review.note(),summary.evidenceVersion()});
            }
        }
        finish(devices,index,DEVICE.length);
        Sheet audios=sheet(book,"录音关联",AUDIO,new int[]{22,68,22,18,22,16,23,14,18,40,32,22,40,44,24,68,26,26,18,22,26,24,28,23,23,22,20,22,18,23,44,68},
                criteria+"\n"+history,"相同文件按完整 SHA-256 + 字节数关联，不做声音或录制者识别。同一拜访多个相同片段只计一次；其他次数不含本次，更早仅按严格早于的提交时间。首次提交销售不是录制者。",styles);
        audios.createFreezePane(1,5);
        index=5;
        for(var group:risk.audios()) {
            var summary=group.summary();var review=group.review();String firstSales=firstSales(group);
            for(var visit:group.visits()) {
                var m=visit.member();
                values(audios.createRow(index++),styles,new Object[]{summary.code(),m.sha256(),m.sizeBytes(),summary.durationMs()==null?null:summary.durationMs()/1000.0,
                        label(summary.durationSource()),yes(visit.matchesFilter()),m.submittedAt(),m.city(),m.salespersonName(),m.salespersonId(),m.storeName(),
                        m.deviceId()==null?"未留存":TemporaryCheckinRiskRepository.code("DEVICE",m.deviceId()),m.submissionId(),joined(visit,TemporaryCheckinRiskRepository.Member::originalFilename),
                        visit.segments().stream().map(TemporaryCheckinRiskRepository.Member::segmentId).filter(Objects::nonNull).distinct().count(),
                        joined(visit,TemporaryCheckinRiskRepository.Member::segmentId),summary.historyCount(),group.matchedCount(),summary.historyCount()-1,visit.earlierCount(),
                        summary.salespersonCount(),summary.dateCount(),firstSales,summary.firstSubmittedAt(),summary.lastSubmittedAt(),label(summary.reviewStatus()),yes(summary.newEvidence()),
                        review==null?"未复核":label(review.status()),review==null?null:review.actor(),review==null?null:review.reviewedAt(),review==null?null:review.note(),summary.evidenceVersion()});
            }
        }
        finish(audios,index,AUDIO.length);
    }
    private static String firstSales(TemporaryCheckinRiskWorkbookEvidence.Group group) {
        return group.visits().stream().map(TemporaryCheckinRiskWorkbookEvidence.Visit::member)
                .filter(m->m.submittedAt().equals(group.summary().firstSubmittedAt())).map(TemporaryCheckinRiskRepository.Member::salespersonName)
                .filter(Objects::nonNull).distinct().sorted().collect(Collectors.joining("、"));
    }
    private static String joined(TemporaryCheckinRiskWorkbookEvidence.Visit visit,Function<TemporaryCheckinRiskRepository.Member,String> field) {
        return visit.segments().stream().map(field).filter(Objects::nonNull).distinct().sorted().collect(Collectors.joining("\n"));
    }
    private static String yes(boolean value) {return value?"是":"否";}
    private static String[] append(String[] a,String[] b) {String[] all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
    private static int[] append(int[] a,int[] b) {int[] all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}
    private static Object[] append(Object[] a,Object[] b) {Object[] all=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,all,a.length,b.length);return all;}

    private static Sheet sheet(Workbook book,String name,String[] headers,int[] widths,String criteria,String note,Styles s) {
        Sheet sheet=book.createSheet(name);sheet.setDisplayGridlines(false);sheet.setDefaultRowHeightInPoints(25);
        Row title=sheet.createRow(0);title.setHeightInPoints(36);Cell cell=title.createCell(0);cell.setCellValue("销售拜访 · "+name);cell.setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0,0,0,headers.length-1));
        for(int r=1;r<=2;r++) {Row row=sheet.createRow(r);row.setHeightInPoints(r==1?48:30);Cell c=row.createCell(0);c.setCellValue(r==1?criteria:note);c.setCellStyle(s.note);sheet.addMergedRegion(new CellRangeAddress(r,r,0,headers.length-1));}
        sheet.createRow(3).setHeightInPoints(8);
        Row header=sheet.createRow(4);header.setHeightInPoints(44);
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
            case "EXPLAINED" -> "已有说明";case "INCONCLUSIVE" -> "证据不足";case "NOT_REQUIRED" -> "无需关联复核";
            case "PERSONAL" -> "个人使用登记";case "SHARED" -> "共用登记";case "UNCONFIRMED" -> "归属未确认";
            case "SERVER_PARSED" -> "服务端解析";case "CLIENT_ESTIMATE" -> "客户端估计";case "UNKNOWN" -> "未知";
            case "SHARED_DEVICE", "DEVICE_MULTIPLE_SALES" -> "同浏览器关联多名销售";
            case "AUDIO_DUPLICATE", "DUPLICATE" -> "相同录音文件用于多次拜访";
            case "AUDIO_CROSS_SALES", "CROSS_SALES" -> "相同录音文件跨销售";case "AUDIO_CROSS_DATE" -> "相同录音文件跨日期";
            case "SALESPERSON_MULTIPLE_DEVICES" -> "同销售使用多个浏览器设备";case "IP_CHURN" -> "访问网络变化";
            case "SHARED_IP_MULTIPLE_SALES" -> "同网络关联多名销售";case "LOCATION_UNVERIFIED" -> "位置未核验";
            case "LOCATION_LOW_ACCURACY" -> "定位精度偏低";case "LOCATION_STALE" -> "定位较早";case "LOCATION_TIME_UNKNOWN" -> "定位时间未知";
            case "LOCATION_MISSING" -> "未取得定位";case "LOCATION_OUT_OF_RANGE" -> "超出距离提醒";case "LOCATION_USER_REPORTED" -> "已报告位置不准";
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
            header=book.createCellStyle();header.cloneStyleFrom(title);header.setFont(white);header.setAlignment(HorizontalAlignment.LEFT);header.setWrapText(true);
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
