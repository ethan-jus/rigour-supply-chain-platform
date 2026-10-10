package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/** 每个交易只计一次付款上限；只对有依据的分配求和，不按整笔回款套用到多个交易。 */
public final class PaymentVoucherAuditEngine {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private PaymentVoucherAuditEngine() {}
    private static String number(String x) { return x==null?"":x.trim().toUpperCase(Locale.ROOT); }
    private static String image(String key) {
        // 此导入目录的文件名已验证由原图内容SHA256生成，其他附件仅按相同对象识别。
        if (key.contains("/voucher-audit-20261009/")) {
            String file=key.substring(key.lastIndexOf('/')+1);
            if(file.matches("[a-f0-9]{64}\\.[a-zA-Z]+")) return "sha256:"+file.substring(0,64);
        }
        return "object:"+key;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static List<Evidence> evidence(Payment p) {
        var items=new LinkedHashMap<String,Evidence>();
        for(var e:p.evidence()) items.put(image(e.key()),e);
        for(var key:p.attachmentKeys()) items.putIfAbsent(image(key),new Evidence(key,null,null,"尚未识别"));
        return new ArrayList<>(items.values());
    }
    private static Set<String> numbers(Payment p) {
        var values=evidence(p).stream().map(e->number(e.transactionNo())).filter(s->!s.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        if(!number(p.primaryTransaction()).isEmpty()) values.add(number(p.primaryTransaction()));
        return values;
    }
    private static BigDecimal allocated(Payment p,String transaction) {
        var ev=evidence(p); var numbers=numbers(p);
        if(numbers.size()==1 && (ev.isEmpty() || ev.stream().allMatch(e->number(e.transactionNo()).equals(transaction)))) return p.amount();
        // 多图必须能逐笔解释整笔回款；相同交易号的重复截图不能重复累加。
        var parts=new LinkedHashMap<String,BigDecimal>();
        for(var e:ev) {
            if(e.amount()==null || e.amount().signum()<=0) return null;
            String key=number(e.transactionNo()).isEmpty()?image(e.key()):"txn:"+number(e.transactionNo());
            BigDecimal old=parts.putIfAbsent(key,e.amount());
            if(old!=null && old.compareTo(e.amount())!=0) return null;
        }
        if(parts.values().stream().reduce(ZERO,BigDecimal::add).compareTo(p.amount())!=0) return null;
        return parts.get("txn:"+transaction);
    }
    public static Scan scan(List<Payment> payments,Map<String,List<Review>> history) {
        var tx=new TreeMap<String,List<Payment>>(); var images=new TreeMap<String,List<Payment>>();
        for(var p:payments) {
            for(var n:numbers(p)) tx.computeIfAbsent(n,k->new ArrayList<>()).add(p);
            for(var e:evidence(p)) images.computeIfAbsent(image(e.key()),k->new ArrayList<>()).add(p);
        }
        var groups=new ArrayList<AuditGroup>();
        for(var entry:tx.entrySet()) groups.add(group("TRANSACTION",entry.getKey(),entry.getValue(),history));
        for(var entry:images.entrySet()) {
            var active=entry.getValue().stream().filter(p->!p.excluded()).toList();
            var ns=active.stream().flatMap(p->numbers(p).stream()).collect(Collectors.toSet());
            // 同图同号已由交易分组处理；不同号/无号复用额外列出图片线索。
            if(active.size()>1 && (ns.size()!=1 || active.stream().anyMatch(p->numbers(p).isEmpty())))
                groups.add(group("IMAGE",entry.getKey(),entry.getValue(),history));
        }
        for(var p:payments) if(!p.excluded() && numbers(p).isEmpty()) {
            groups.add(group("PAYMENT",p.id(),List.of(p),history));
        }
        groups.removeIf(g->g.payments().stream().allMatch(Payment::excluded));
        List<String> priority=List.of("EXCESS","AMOUNT_MISMATCH","CONFLICT","IMAGE_REUSE","PENDING_ALLOCATION","MISSING_EVIDENCE","UNALLOCATED","BALANCED");
        groups.sort(Comparator.comparingInt((AuditGroup g)->priority.indexOf(g.result())).thenComparing(AuditGroup::key));
        var counts=new LinkedHashMap<String,Long>();
        for(var status:priority) counts.put(status,groups.stream().filter(g->status.equals(g.result())).count());
        return new Scan(Instant.now(),payments.size(),(int)payments.stream().filter(p->!p.attachmentKeys().isEmpty()).count(),counts,groups);
    }
    private static AuditGroup group(String kind,String value,List<Payment> source,Map<String,List<Review>> history) {
        var unique=new TreeMap<String,Payment>(); source.forEach(p->unique.put(p.id(),p));
        var rows=new ArrayList<>(unique.values()); var amounts=new TreeSet<BigDecimal>();
        var reasons=new ArrayList<String>(); BigDecimal total=ZERO; int unresolved=0;
        String firstImage=null;
        for(var p:rows) {
            if(p.excluded()) continue;
            var matched=evidence(p).stream().filter(e->kind.equals("TRANSACTION")?number(e.transactionNo()).equals(value):kind.equals("IMAGE")&&image(e.key()).equals(value)).toList();
            for(var e:matched) { if(firstImage==null) firstImage=e.key(); if(e.amount()!=null&&e.amount().signum()>0)amounts.add(e.amount()); }
            BigDecimal part=kind.equals("TRANSACTION")?allocated(p,value):kind.equals("IMAGE")&&evidence(p).size()==1?p.amount():null;
            if(part==null || part.signum()<=0) unresolved++; else total=total.add(part);
        }
        BigDecimal amount=amounts.size()==1?amounts.first():null;
        BigDecimal excess=amount==null?null:total.subtract(amount).max(ZERO);
        String result;
        if(amounts.size()>1) {result="CONFLICT";reasons.add("同一交易或图片登记了不同的凭证金额，请核对原图和识别结果");}
        else if(excess!=null&&excess.signum()>0) {result=rows.stream().filter(p->!p.excluded()).count()>1?"EXCESS":"AMOUNT_MISMATCH";reasons.add("已明确归属到该付款的有效回款合计超出凭证金额；差额也可能来自优惠、抹零或识别偏差，需核对原图");}
        else if(kind.equals("IMAGE")) {result="IMAGE_REUSE";reasons.add("相同原图或附件被多条回款使用，交易单号缺失或不一致");}
        else if(unresolved>0) {result="PENDING_ALLOCATION";reasons.add("多张凭证或多个交易号尚无法完整解释回款分配，不作金额相符判定");}
        else if(amount==null) {result="MISSING_EVIDENCE";reasons.add("缺少可确认的凭证付款金额，无法核对上限");}
        else if(total.compareTo(amount)<0) {result="UNALLOCATED";reasons.add("凭证金额大于当前权限范围内已关联回款，可能存在未分配或其他范围的订单");}
        else {result="BALANCED";reasons.add("当前权限范围内金额相符；金额相符不代表已确认凭证真实性");}
        if(kind.equals("PAYMENT")) {result="MISSING_EVIDENCE";reasons.clear();reasons.add("尚无可确认交易单号，无法完成跨单核查");}
        if(rows.stream().map(Payment::customer).filter(Objects::nonNull).distinct().count()>1) reasons.add("涉及多个客户，请核对是否为代付或合并付款");
        if(rows.stream().map(Payment::salesperson).filter(Objects::nonNull).distinct().count()>1) reasons.add("涉及多个业务员，请核对归属");
        if(rows.stream().anyMatch(Payment::excluded)) reasons.add("已取消、已删除及未收款记录仅供追溯，不计入本次金额");
        String key=kind+":"+hash(value);
        String fingerprint=hash(JSON.writeValueAsString(rows));
        // 不展示包含当前用户不可见回款的历史结论，避免通过备注绕过数据权限。
        var reviews=history.getOrDefault(key,List.of()).stream().filter(r->unique.keySet().containsAll(r.paymentIds())).toList();
        return new AuditGroup(key,kind,kind.equals("TRANSACTION")?value:null,firstImage,result,reasons,amount,total,excess,unresolved,rows,
            fingerprint,reviews,!reviews.isEmpty()&&!reviews.get(0).fingerprint().equals(fingerprint));
    }
}
