package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Excel与列表共享权限、过滤和一致读；超过上限明确拒绝，不导出被截断的统计。 */
@Service
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinWorkbookService {
    private final TemporaryCheckinService service;
    private final TemporaryCheckinRepository repository;
    private final TemporaryCheckinStatisticsRepository statistics;
    private final TemporaryCheckinEvidenceRepository evidence;
    private final TemporaryCheckinWorkbookWriter writer;
    private final UUID tenant;
    private final java.util.concurrent.Semaphore exportSlot=new java.util.concurrent.Semaphore(1);

    TemporaryCheckinWorkbookService(TemporaryCheckinService service, TemporaryCheckinRepository repository,
            TemporaryCheckinStatisticsRepository statistics, TemporaryCheckinEvidenceRepository evidence,
            TemporaryCheckinWorkbookWriter writer, TemporaryCheckinProperties properties) {
        this.service=service; this.repository=repository; this.statistics=statistics;
        this.evidence=evidence; this.writer=writer; this.tenant=properties.requireTenantId();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] export(AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId,
            String status, String visitType, String query, TemporaryCheckinRepository.AdminReadOptions options) {
        return export(scope, from, to, city, salespersonId, status, visitType, query, options, null, null);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] export(AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId,
            String status, String visitType, String query, TemporaryCheckinRepository.AdminReadOptions options,
            String summarySortBy, String summarySortDirection) {
        var filter=service.normalizeAdminQuery(scope,from,to,city,salespersonId,status,visitType,query);
        var summarySort=new TemporaryCheckinStatisticsRepository.SummarySort(summarySortBy,summarySortDirection);
        if(!exportSlot.tryAcquire()) throw TemporaryCheckinException.conflict("正在生成另一份报表，请稍后重试");
        try {
        var rows=repository.exportForWorkbook(tenant,filter.from(),filter.toExclusive(),filter.city(),filter.salespersonId(),
                filter.status(),filter.visitType(),filter.escapedQuery(),20001,options);
        if(rows.size()>20000) throw TemporaryCheckinException.badRequest("导出超过20000条，请缩小日期或城市范围");
        var ids=rows.stream().map(TemporaryCheckinRepository.ExportRow::id).toList();
        var summary=statistics.aggregate(tenant,filter,options,summarySort);
        String criteria="日期："+(from==null?"不限":from)+" 至 "+(to==null?"不限":to)
                +"  ｜  城市："+(filter.city()==null?"权限内全部":filter.city())
                +"  ｜  销售："+(salespersonId==null?"全部":rows.stream().map(TemporaryCheckinRepository.ExportRow::salespersonName).findFirst().orElse("指定销售"))
                +"  ｜  状态："+(status==null||status.isBlank()?"全部":TemporaryCheckinWorkbookWriter.label(status))
                +"  ｜  复核："+(options.reviewStatus()==null?"全部":TemporaryCheckinWorkbookWriter.label(options.reviewStatus()))
                +"  ｜  定位："+(options.locationStatus()==null?"全部":TemporaryCheckinWorkbookWriter.label(options.locationStatus()))
                +"  ｜  拜访类型："+(visitType==null||visitType.isBlank()?"全部":("REVISIT".equals(visitType)?"回访":"首次拜访"))
                +"  ｜  媒体："+(options.mediaStatus()==null?"全部":switch(options.mediaStatus()){case "HAS_AUDIO"->"有录音";case "MISSING_AUDIO"->"无录音";default->"无现场照片";})
                +"  ｜  关键词："+(query==null||query.isBlank()?"无":query)+"  ｜  匹配记录："+rows.size()+" 条"
                +"  ｜  每日汇总排序："+summarySort.description();
        return writer.write(rows,summary.items(),evidence.evidenceBatch(tenant,ids),repository.photoCounts(tenant,ids),criteria);
        } finally { exportSlot.release(); }
    }
}
