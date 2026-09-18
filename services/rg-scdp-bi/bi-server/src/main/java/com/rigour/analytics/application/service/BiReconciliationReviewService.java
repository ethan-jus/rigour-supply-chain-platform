package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.*;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore;
import com.rigour.analytics.application.port.out.BiReconciliationUnitDictionary;
import com.rigour.analytics.application.port.out.BiOrderRepairEvidenceSource;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 来源版本复核用例：显式采集、局部证据、全局授权；不提供恢复/覆盖业务数据的写入口。 */
@Service
public class BiReconciliationReviewService {
    static final int CAPTURE_LIMIT=20000;
    private static final BigDecimal CENT=new BigDecimal("0.01");
    private final BiReconciliationReviewStore store;
    private final BiDataScopeService scope;
    private final Clock clock;
    private final BiReconciliationSourceNormalizer source;
    private final BiReconciliationUnitDictionary units;
    private final BiOrderRepairEvidenceSource repairEvidence;

    public BiReconciliationReviewService(BiReconciliationReviewStore store, BiDataScopeService scope,
                                         Clock analyticsClock, ObjectMapper json, BiReconciliationUnitDictionary units) {
        this(store, scope, analyticsClock, json, units, actor -> List.of());
    }

    @Autowired
    public BiReconciliationReviewService(BiReconciliationReviewStore store, BiDataScopeService scope,
                                         Clock analyticsClock, ObjectMapper json, BiReconciliationUnitDictionary units,
                                         BiOrderRepairEvidenceSource repairEvidence) {
        this.store=store; this.scope=scope; this.clock=analyticsClock;
        this.source=new BiReconciliationSourceNormalizer(json);
        this.units=units;
        this.repairEvidence=repairEvidence;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Page capture(Command command) {
        CallerIdentity actor=actor();
        AuthorizationContext.requirePermission("analytics:reconciliation:write");
        if (command==null) throw bad("请指定飞书预检批次或在线采集和完整统计期间");
        boolean online=command.onlineCaptureId()!=null;
        if ((command.batchId()!=null)==online) throw bad("文件批次与在线采集必须且只能选择一个");
        String batch=online?null:uuid(command.batchId());
        String captureId=online?uuid(command.onlineCaptureId()):null;
        Instant now=clock.instant();
        if (command.from()==null || command.to()==null || command.from().isAfter(command.to())
                || Duration.between(command.from(),command.to()).toDays()>1096) throw bad("对账期间不能为空且不超过三年");
        if (!online && command.sourceExportedAt()!=null && command.sourceExportedAt().isAfter(now)) throw bad("来源导出时间不能晚于当前时间");
        String tenant=actor.tenantId().toString();
        var captured=online?store.onlineCapture(tenant,captureId)
                .orElseThrow(()->bad("未找到当前租户完整成功的在线采集，请重新采集后复核")):null;
        Version version=online?captured.version():store.version(tenant,batch)
                .orElseThrow(()->bad("未找到当前租户的来源预检批次"));
        if (online && (version.onlineEvidence()==null || !version.onlineEvidence().complete()
                || version.onlineEvidence().completedAt()==null || version.onlineEvidence().completedAt().isAfter(now))) {
            throw bad("在线采集证据不完整或采集窗口无效");
        }
        if ("REJECTED".equals(version.importStatus())) throw bad("来源预检未通过，不能创建复核");
        if ("RUNNING".equals(version.importStatus())) throw bad("来源批次正在执行导入，请等待批次结束再创建复核");
        Version previous=command.previousBatchId()==null?null:store.version(tenant,uuid(command.previousBatchId()))
                .orElseThrow(()->bad("历史来源批次不存在"));
        if (previous!=null && (previous.batchId().equals(batch) || previous.uploadedAt()==null
                || previous.uploadedAt().isAfter(online?version.onlineEvidence().startedAt():version.uploadedAt()))) {
            throw bad("历史批次必须早于本次来源批次且不能相同");
        }
        List<Fact> business=store.businessRows(tenant,CAPTURE_LIMIT+1), bi=store.biRows(tenant,CAPTURE_LIMIT+1);
        // 历史来源不能借用本次采集的人工确认；保留覆盖前的只读目录用于历史版本比较。
        List<Fact> historicalCatalog=new ArrayList<>(business); historicalCatalog.addAll(bi);
        String repairNotice=null;
        try {
            var evidence=repairEvidence.current(actor);
            business=BiReconciliationRepairEvidence.apply(business,evidence,version);
            bi=BiReconciliationRepairEvidence.apply(bi,evidence,version);
        } catch (RuntimeException unavailable) {
            repairNotice="已确认的商品修复记录暂未读取，本次仅按原始记录核对；请恢复订单读取权限或服务后重试。";
        }
        List<Fact> catalog=new ArrayList<>(business); catalog.addAll(bi);
        List<Map<String,Object>> latestRaw=online?captured.rows():raw(tenant,batch);
        if (latestRaw.size()>CAPTURE_LIMIT || online && latestRaw.isEmpty()) throw bad("在线来源为空或超过20000行，未创建复核");
        List<Fact> latest=source.normalize(latestRaw,catalog);
        List<Fact> earlier=previous==null?List.of():source.normalize(raw(tenant,previous.batchId()),historicalCatalog);
        Map<String,String> dictionary=units.productUnits(actor);
        latest=normalizeUnits(latest,dictionary);
        earlier=normalizeUnits(earlier,dictionary);
        business=normalizeUnits(business,dictionary);
        bi=normalizeUnits(bi,dictionary);
        Command effective=online?new Command(null,command.previousBatchId(),command.from(),command.to(),null,
                captured.sourceComplete(),captureId):command;
        var review=compare(UUID.randomUUID().toString(),effective,version,previous,latest,business,bi,earlier,now,clock.instant());
        if (repairNotice!=null) {
            var notices=new ArrayList<>(review.notices()); notices.add(repairNotice);
            review=new BiReconciliationReview(review.id(),review.from(),review.to(),review.capturedAt(),review.completedAt(),
                    review.sourceVersion(),review.previousVersion(),review.onlineStatus(),review.status(),
                    review.sourceDeclaredComplete(),review.sourceExportedAt(),List.copyOf(notices),review.rows());
        }
        store.save(tenant,actor.userId().toString(),review);
        return page(review,"ORDER",null,null,null,null,null,1,50);
    }

    static List<Fact> normalizeUnits(List<Fact> facts,Map<String,String> dictionary) {
        return facts.stream().map(f->{
            if (!"SKU".equals(f.kind())) return f;
            String original=f.rawUnit()==null?f.unit():f.rawUnit();
            String raw=f.confirmedUnitCode()==null?original:f.confirmedUnitCode();
            String code=null;
            if (raw!=null) {
                if (dictionary.containsKey(raw)) code=raw;
                else {
                    var candidates=dictionary.entrySet().stream().filter(e->raw.equals(e.getValue())).map(Map.Entry::getKey).toList();
                    if (candidates.size()==1) code=candidates.getFirst();
                }
            }
            var issues=new ArrayList<>(f.uncertainties());
            if (code==null) issues.add("原单位未能在PRODUCT_UNIT字典中唯一解析，数量单位未核验");
            return new Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),f.product(),f.specification(),
                    code==null?raw:dictionary.get(code),f.orderDate(),f.amount(),f.paid(),f.unpaid(),f.quantity(),f.updatedAt(),
                    f.sourceRows(),f.excludedRefund(),List.copyOf(issues),original,code,f.unitEvidence(),f.associationEvidence(),
                    f.sourceRecordId(),f.sourceProductId(),f.sourceProductCode(),f.systemLineId(),f.systemVariantId(),f.confirmedUnitCode());
        }).toList();
    }

    public Page get(String id,String kind,String status,String city,String sales,String orderNo,String keyword,int page,int pageSize) {
        var actor=actor();
        var review=store.find(actor.tenantId().toString(),actor.userId().toString(),uuid(id))
                .orElseThrow(()->bad("复核记录不存在或不在当前授权范围内"));
        return page(review,kind,status,city,sales,orderNo,keyword,page,pageSize);
    }
    public List<History> history() {
        var actor=actor();
        return store.history(actor.tenantId().toString(),actor.userId().toString());
    }
    private List<Map<String,Object>> raw(String tenant,String batch) {
        var rows=store.sourceRows(tenant,batch,CAPTURE_LIMIT+1);
        if (rows.size()>CAPTURE_LIMIT) throw bad("来源订单及明细超过20000行，本次未生成不完整复核；请拆分来源文件");
        return rows;
    }
    private CallerIdentity actor() {
        scope.requireGlobalGovernance();
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        return AuthorizationContext.requireCurrent();
    }

    static BiReconciliationReview compare(String id, Command command, Version version, Version previous,
                                          List<Fact> source, List<Fact> business, List<Fact> bi,
                                          List<Fact> earlier, Instant captured, Instant completed) {
        Map<String,Fact> s=index(source), b=index(business), d=index(bi), p=index(earlier);
        OnlineEvidence online=version.onlineEvidence();
        boolean declaredComplete=online==null?command.sourceDeclaredComplete():online.complete() && !online.filtered();
        var orders=new HashSet<String>();
        for (var rows:List.of(source,business,bi,earlier)) for (Fact f:rows) {
            if ("ORDER".equals(f.kind()) && (f.orderDate()==null || inside(f,command))) orders.add(f.orderNo());
        }
        Set<String> keys=new TreeSet<>();
        for (var rows:List.of(s,b,d,p)) for (Fact f:rows.values()) {
            if (orders.contains(f.orderNo()) || f.orderNo()==null) keys.add(f.key());
        }
        Instant sourceTime=online!=null?online.completedAt():command.sourceExportedAt()==null?version.uploadedAt():command.sourceExportedAt();
        boolean stale=Duration.between(sourceTime,captured)
                .compareTo(Duration.ofHours(6))>0;
        List<Row> rows=new ArrayList<>();
        for (String key:keys) {
            Fact sf=s.get(key), bf=b.get(key), df=d.get(key), pf=p.get(key);
            Fact display=sf!=null?sf:bf!=null?bf:df!=null?df:pf;
            List<String> issues=new ArrayList<>();
            String status;
            String financial=assessment(sf,bf,df,"FINANCIAL");
            String quantity="ORDER".equals(display.kind())?"NOT_APPLICABLE":assessment(sf,bf,df,"QUANTITY");
            String association=assessment(sf,bf,df,"ASSOCIATION");
            if (sf!=null && sf.excludedRefund()) {
                status=bf==null && df==null?"EXCLUDED_REFUND":"DIFF";
                issues.add("零数量退款/冲销订单按已确认规则排除，不补录；原始金额和行仍留作审计");
                if (bf!=null || df!=null) issues.add("已排除来源仍存在业务或BI记录，需要人工复核，不自动删除");
                financial=status; quantity=status; association=status;
            } else {
                boolean scopeMismatch=List.of(Optional.ofNullable(sf),Optional.ofNullable(bf),Optional.ofNullable(df)).stream()
                        .flatMap(Optional::stream).anyMatch(f->f.orderDate()!=null && !inside(f,command));
                if (scopeMismatch) issues.add("同一订单在来源/业务/BI的业务日期落入不同筛选范围");
                if (sf==null) issues.add("所选来源快照没有此记录，不代表当前在线记录已删除");
                if (sf!=null) issues.addAll(sf.uncertainties());
                if (bf!=null) issues.addAll(bf.uncertainties());
                if (df!=null) issues.addAll(df.uncertainties());
                boolean uncertain=scopeMismatch || sf==null || sf.orderDate()==null || !issues.isEmpty();
                List<String> discrepancies=new ArrayList<>();
                if (bf==null) discrepancies.add("业务记录缺失");
                if (df==null) discrepancies.add("BI记录缺失");
                differences(sf,bf,"来源与业务",discrepancies);
                List<String> businessBiDifferences=new ArrayList<>();
                differences(bf,df,"业务与BI",businessBiDifferences);
                discrepancies.addAll(businessBiDifferences);
                boolean businessBiDiff=(bf==null)!=(df==null) || !businessBiDifferences.isEmpty();
                // 不完整或无法关联的来源不能把“没看见”升级为漏导结论。
                boolean confirmedDifference=List.of(financial,quantity,association).contains("DIFF");
                if ((!scopeMismatch && (businessBiDiff || confirmedDifference)) || (!uncertain && !discrepancies.isEmpty())) status="DIFF";
                else if (uncertain || !declaredComplete) status="UNVERIFIED";
                else if (List.of(financial,quantity,association).contains("UNVERIFIED")) status="UNVERIFIED";
                else if (stale) status="STALE";
                else status="SNAPSHOT_MATCH";
                issues.addAll(discrepancies);
                if (stale) issues.add("来源采集距本次复核超过6小时，仅能核对历史版本");
                if (!declaredComplete) issues.add(online==null?"来源未声明为完整导出，缺失记录不能作为漏导证据":
                        "在线来源有视图过滤或完整性证据不足，缺失记录不能作为漏导或删除证据");
            }
            String changed=previous==null?"NOT_COMPARED":pf==null?"ADDED":sf==null?"ABSENT_IN_SELECTED_VERSION":
                    equivalent(pf,sf)?"UNCHANGED":"CHANGED";
            rows.add(new Row(key,display.orderNo(),display.kind(),display.city(),display.sales(),status,changed,sf,bf,df,pf,
                    List.copyOf(issues),financial,quantity,association));
        }
        // 订单金额相抵不能掩盖 SKU 差异；订单级结论包含子明细是否可核验。
        Map<String,List<Row>> children=rows.stream().filter(r->"SKU".equals(r.kind()) && r.orderNo()!=null)
                .collect(Collectors.groupingBy(Row::orderNo));
        rows=rows.stream().map(r->{
            if (!"ORDER".equals(r.kind()) || "EXCLUDED_REFUND".equals(r.status()) || "DIFF".equals(r.status())) return r;
            var detail=children.getOrDefault(r.orderNo(),List.of());
            boolean diff=detail.stream().anyMatch(x->"DIFF".equals(x.status()));
            boolean unknown=detail.isEmpty() || detail.stream().anyMatch(x->List.of("UNVERIFIED","STALE").contains(x.status()));
            if (!diff && !unknown) return r;
            var notes=new ArrayList<>(r.issues());
            notes.add(diff?"订单下SKU存在差异":"订单下SKU明细尚未完整核验");
            return new Row(r.key(),r.orderNo(),r.kind(),r.city(),r.sales(),diff?"DIFF":"UNVERIFIED",r.versionStatus(),
                    r.source(),r.business(),r.bi(),r.previous(),List.copyOf(notes),r.financialStatus(),
                    r.quantityStatus(),r.associationStatus());
        }).toList();
        List<String> notices=new ArrayList<>(List.of(
                online==null?"来源是指定上传批次，上传时间不是在线更新时间；本次未调用飞书在线API，当前在线状态未验证。":
                        "来源是在线只读采集，采集窗口 "+online.startedAt()+" 至 "+online.completedAt()+
                                "；完整分页不等于原子快照，期间可能有修改，不代表持续最新或当前在线一致。",
                "业务/BI只比较FEISHU有效订单；退款零数量订单按约定排除并保留原始审计。",
                "同一来源订单号在任一层落入所选下单期间即纳入核对，跨层日期不同标为范围不一致；回款为订单累计回款，不是期间现金流。",
                "SKU优先核对来源商品关联与编码；名称/规格只定位候选。列名推断单位标为待确认，不当作已证实的单位错误；未归属回款不分摊到SKU。",
                "金额、数量单位、商品及经营归属分别核验；订单头金额相同不代表商品销量可用于订货报批。",
                "差额按原始精度核算，金额容差0.01元；页面显示两位小数；本次操作不改写订单、回款或飞书。",
                "本轮核对订单、累计回款、城市/销售/客户归属及可关联SKU金额数量；ERP品牌、分类树及其迁移映射尚未在本复核中验证。"));
        if (source.isEmpty()) notices.add("来源批次没有可识别的销售订单/订单明细，不能判为一致。");
        if (previous!=null) notices.add("历史变化仅说明两个所选来源版本之间的差异，不证明在线删除或新增；不同导出视图或期间也可能产生本次未出现的记录。");
        if (online!=null && !declaredComplete) notices.add("当前采集为受限视图或不完整来源范围，即使数值相同也不判为全量一致。");
        if (stale) notices.add(online==null?"所选来源已超过6小时，建议上传最新在线导出重新核对。":"所选在线采集已超过6小时，请重新采集后复核。");
        String state=rows.stream().anyMatch(r->"DIFF".equals(r.status()))?"DIFF":
                rows.isEmpty() || !declaredComplete || rows.stream().anyMatch(r->"UNVERIFIED".equals(r.status()))?"UNVERIFIED":stale?"STALE":"SNAPSHOT_MATCH";
        return new BiReconciliationReview(id,command.from(),command.to(),captured,completed,version,previous,
                online==null?"UNVERIFIED":"CAPTURED_NON_ATOMIC",state,declaredComplete,online==null?command.sourceExportedAt():null,List.copyOf(notices),rows);
    }

    static Page page(BiReconciliationReview review,String kind,String status,String city,String sales,
                     String orderNo,String keyword,int page,int pageSize) {
        if (!List.of("ORDER","SKU").contains(kind)) throw bad("不支持的对账粒度");
        if (page<1 || pageSize<1 || pageSize>100) throw bad("页码必须大于0，每页1至100条");
        String term=keyword==null?"":keyword.strip().toLowerCase(Locale.ROOT);
        List<Row> selected=review.rows().stream().filter(r->city==null || Objects.equals(city,Objects.toString(r.city(),"未归属")))
                .filter(r->sales==null || Objects.equals(sales,Objects.toString(r.sales(),"未归属")))
                .filter(r->orderNo==null || Objects.equals(orderNo,r.orderNo())).toList();
        List<Row> rows=selected.stream().filter(r->kind.equals(r.kind()))
                .filter(r->status==null || status.equals(r.status()))
                .filter(r->term.isEmpty() || searchText(r).toLowerCase(Locale.ROOT).contains(term))
                .sorted(Comparator.comparing(Row::key)).toList();
        int start=(int)Math.min((long)(page-1)*pageSize,rows.size());
        return new Page(review.id(),review.from(),review.to(),review.capturedAt(),review.completedAt(),
                review.sourceVersion(),review.previousVersion(),review.onlineStatus(),review.status(),
                review.sourceDeclaredComplete(),review.sourceExportedAt(),review.notices(),summary(selected),
                dimensions(review.rows(),Row::city),dimensions(review.rows(),Row::sales),rows.size(),page,pageSize,
                List.copyOf(rows.subList(start,Math.min(start+pageSize,rows.size()))));
    }
    private static String searchText(Row row) {
        return Objects.toString(row.orderNo(),"")+" "+Objects.toString(row.city(),"")+" "+Objects.toString(row.sales(),"")+" "+
                List.of(Optional.ofNullable(row.source()),Optional.ofNullable(row.business()),Optional.ofNullable(row.bi())).stream()
                        .flatMap(Optional::stream).map(f->Objects.toString(f.product(),"")+" "+Objects.toString(f.customer(),""))
                        .collect(Collectors.joining(" "));
    }
    private static List<Dimension> dimensions(List<Row> rows,Function<Row,String> field) {
        return rows.stream().filter(r->"ORDER".equals(r.kind())).collect(Collectors.groupingBy(r->Objects.toString(field.apply(r),"未归属"),TreeMap::new,Collectors.toList()))
                .entrySet().stream().map(e->new Dimension(e.getKey(),summary(e.getValue()))).toList();
    }
    private static Summary summary(List<Row> rows) {
        List<Row> orders=rows.stream().filter(r->"ORDER".equals(r.kind())).toList();
        List<Row> included=orders.stream().filter(r->!"EXCLUDED_REFUND".equals(r.status())).toList();
        return new Summary(orders.size(),count(orders,"SNAPSHOT_MATCH"),count(orders,"DIFF"),count(orders,"UNVERIFIED")+count(orders,"STALE"),
                count(orders,"EXCLUDED_REFUND"),sum(included,Row::source,Fact::amount),sum(included,Row::business,Fact::amount),sum(included,Row::bi,Fact::amount),
                sum(included,Row::source,Fact::paid),sum(included,Row::business,Fact::paid),sum(included,Row::bi,Fact::paid));
    }
    private static long count(List<Row> rows,String status) { return rows.stream().filter(r->status.equals(r.status())).count(); }
    private static BigDecimal sum(List<Row> rows,Function<Row,Fact> layer,Function<Fact,BigDecimal> value) {
        if (rows.isEmpty()) return null;
        BigDecimal total=BigDecimal.ZERO;
        for (Row row:rows) {
            Fact fact=layer.apply(row);
            if (fact==null || value.apply(fact)==null) return null;
            total=total.add(value.apply(fact));
        }
        return total;
    }
    private static boolean inside(Fact fact,Command c) { return fact.orderDate()!=null && !fact.orderDate().isBefore(c.from()) && !fact.orderDate().isAfter(c.to()); }
    private static Map<String,Fact> index(List<Fact> rows) {
        Map<String,Fact> result=new LinkedHashMap<>();
        for (Fact row:rows) result.merge(row.key(),row,(a,b)->{
            var issues=new ArrayList<>(a.uncertainties()); issues.addAll(b.uncertainties());
            if ("ORDER".equals(a.kind())) issues.add("来源订单号重复，不能判定为唯一订单");
            boolean compatible=trustedUnit(a) && trustedUnit(b) && Objects.equals(comparisonUnit(a),comparisonUnit(b));
            if ("SKU".equals(a.kind()) && !compatible) issues.add("同SKU存在多种原单位或单位未确认，不合并数量");
            return new Fact(a.key(),a.orderNo(),a.kind(),a.city(),a.sales(),a.customer(),a.product(),a.specification(),a.unit(),a.orderDate(),
                    add(a.amount(),b.amount()),add(a.paid(),b.paid()),add(a.unpaid(),b.unpaid()),compatible?add(a.quantity(),b.quantity()):null,
                    a.updatedAt(),Objects.toString(a.sourceRows(),"")+"; "+Objects.toString(b.sourceRows(),""),a.excludedRefund(),List.copyOf(issues),a.rawUnit(),a.unitCode(),
                    mergedEvidence(a.unitEvidence(),b.unitEvidence()),mergedEvidence(a.associationEvidence(),b.associationEvidence()),
                    Objects.equals(a.sourceRecordId(),b.sourceRecordId())?a.sourceRecordId():null,
                    Objects.equals(a.sourceProductId(),b.sourceProductId())?a.sourceProductId():null,
                    Objects.equals(a.sourceProductCode(),b.sourceProductCode())?a.sourceProductCode():null);
        });
        return result;
    }
    private static BigDecimal add(BigDecimal a,BigDecimal b) { return a==null || b==null?null:a.add(b); }
    private static void differences(Fact a,Fact b,String prefix,List<String> issues) {
        if (a==null || b==null) return;
        if (!trustedAssociation(a) || !trustedAssociation(b)) return;
        amountDiff(a.amount(),b.amount(),prefix+"金额",CENT,issues);
        if ("ORDER".equals(a.kind())) {
            amountDiff(a.paid(),b.paid(),prefix+"累计回款",CENT,issues);
            amountDiff(a.unpaid(),b.unpaid(),prefix+"待回款",CENT,issues);
        } else {
            if (trustedUnit(a) && trustedUnit(b)) {
                if (!Objects.equals(comparisonUnit(a),comparisonUnit(b))) issues.add(prefix+"原单位不同，未自动换算");
                else amountDiff(a.quantity(),b.quantity(),prefix+"数量",new BigDecimal("0.000001"),issues);
            }
        }
        if (!Objects.equals(a.city(),b.city())) issues.add(prefix+"城市归属不同或缺失");
        if (!Objects.equals(a.sales(),b.sales())) issues.add(prefix+"责任销售不同或缺失");
        if (!Objects.equals(a.customer(),b.customer())) issues.add(prefix+"客户名称不同或缺失");
    }
    private static String comparisonUnit(Fact fact) { return fact.unitCode()==null?fact.unit():fact.unitCode(); }
    private static String mergedEvidence(String a,String b) { return Objects.equals(a,b)?a:"MIXED"; }
    private static boolean trustedAssociation(Fact fact) {
        if (fact==null || fact.orderNo()==null || fact.uncertainties().stream().anyMatch(i->i.contains("订单号重复"))) return false;
        return "ORDER".equals(fact.kind()) || Set.of("SYSTEM","SOURCE_RECORD","SOURCE_CODE","OPERATOR_CONFIRMED")
                .contains(Objects.toString(fact.associationEvidence(),""));
    }
    private static boolean trustedUnit(Fact fact) {
        return fact!=null && comparisonUnit(fact)!=null
                && Set.of("EXPLICIT","SYSTEM","OPERATOR_CONFIRMED").contains(Objects.toString(fact.unitEvidence(),""))
                && fact.uncertainties().stream().noneMatch(i->i.contains("PRODUCT_UNIT") || i.contains("多种原单位"));
    }
    /** 分项结论独立于总状态；金额差异不能被未知单位掩盖，名称候选不能冒充可靠关联。 */
    private static String assessment(Fact source,Fact business,Fact bi,String metric) {
        String sourceBusiness=assessPair(source,business,metric), businessBi=assessPair(business,bi,metric);
        if ("DIFF".equals(sourceBusiness) || "DIFF".equals(businessBi)) return "DIFF";
        return "SNAPSHOT_MATCH".equals(sourceBusiness) && "SNAPSHOT_MATCH".equals(businessBi)?"SNAPSHOT_MATCH":"UNVERIFIED";
    }
    private static String assessPair(Fact a,Fact b,String metric) {
        if (!trustedAssociation(a) || !trustedAssociation(b)) return "UNVERIFIED";
        if ("FINANCIAL".equals(metric)) {
            List<String> states=new ArrayList<>();
            states.add(numberStatus(a.amount(),b.amount(),CENT));
            if ("ORDER".equals(a.kind())) {
                states.add(numberStatus(a.paid(),b.paid(),CENT));
                states.add(numberStatus(a.unpaid(),b.unpaid(),CENT));
            }
            return combinedStatus(states);
        }
        if ("QUANTITY".equals(metric)) {
            if (!trustedUnit(a) || !trustedUnit(b)) return "UNVERIFIED";
            if (!Objects.equals(comparisonUnit(a),comparisonUnit(b))) return "DIFF";
            return numberStatus(a.quantity(),b.quantity(),new BigDecimal("0.000001"));
        }
        List<String> states=new ArrayList<>();
        for (var field:List.<Function<Fact,String>>of(Fact::city,Fact::sales,Fact::customer)) {
            String x=field.apply(a),y=field.apply(b);
            states.add(x==null || y==null?"UNVERIFIED":x.equals(y)?"SNAPSHOT_MATCH":"DIFF");
        }
        return combinedStatus(states);
    }
    private static String combinedStatus(List<String> states) {
        return states.contains("DIFF")?"DIFF":states.contains("UNVERIFIED")?"UNVERIFIED":"SNAPSHOT_MATCH";
    }
    private static String numberStatus(BigDecimal a,BigDecimal b,BigDecimal tolerance) {
        return a==null || b==null?"UNVERIFIED":a.subtract(b).abs().compareTo(tolerance)>0?"DIFF":"SNAPSHOT_MATCH";
    }
    private static void amountDiff(BigDecimal a,BigDecimal b,String label,BigDecimal tolerance,List<String> issues) {
        if (a==null || b==null) { issues.add(label+"缺少可比较数值"); return; }
        if (a.subtract(b).abs().compareTo(tolerance)>0) issues.add(label+"差额 "+b.subtract(a).toPlainString());
    }
    private static boolean equivalent(Fact a,Fact b) {
        return equalNumber(a.amount(),b.amount()) && equalNumber(a.paid(),b.paid()) && equalNumber(a.unpaid(),b.unpaid())
                && equalNumber(a.quantity(),b.quantity()) && Objects.equals(a.orderDate(),b.orderDate())
                && Objects.equals(a.city(),b.city()) && Objects.equals(a.sales(),b.sales()) && Objects.equals(a.customer(),b.customer())
                && Objects.equals(a.product(),b.product()) && Objects.equals(a.specification(),b.specification())
                && Objects.equals(a.rawUnit(),b.rawUnit()) && Objects.equals(a.unit(),b.unit())
                && Objects.equals(a.unitEvidence(),b.unitEvidence()) && Objects.equals(a.associationEvidence(),b.associationEvidence())
                && a.excludedRefund()==b.excludedRefund();
    }
    private static boolean equalNumber(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null && a.compareTo(b)==0; }
    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); } catch (RuntimeException ex) { throw bad("无效的复核或批次编号"); }
    }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message,List.of()); }
}
