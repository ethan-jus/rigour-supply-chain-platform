package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只读复核标准化：保留字段缺失、未关联、单位未知，不借用业务金额填补来源。 */
final class BiReconciliationSourceNormalizer {
    private static final Pattern ORDER = Pattern.compile("(?<![A-Za-z0-9])DD\\d+(?!\\d)");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final ObjectMapper json;
    BiReconciliationSourceNormalizer(ObjectMapper json) { this.json=json; }

    List<Fact> normalize(List<Map<String,Object>> raw, List<Fact> catalog) {
        List<Fact> result=new ArrayList<>();
        Map<String,Fact> headers=new HashMap<>();
        Map<String,String> headerRecords=new HashMap<>();
        Map<String,JsonNode> productRecords=new HashMap<>();
        for (var row:raw) if ("FEISHU_PRODUCT".equals(row.get("tableCode")) && row.get("recordId")!=null) {
            productRecords.put(row.get("recordId").toString(),json.readTree(row.get("valuesJson").toString()));
        }
        Map<String,List<Fact>> skuByOrder=catalog.stream().filter(f->"SKU".equals(f.kind()) && f.orderNo()!=null)
                .collect(java.util.stream.Collectors.groupingBy(Fact::orderNo));
        for (var row:raw) if ("FEISHU_SALES_ORDER".equals(row.get("tableCode"))) {
            var value=source(row, false, skuByOrder, headers,headerRecords,productRecords);
            result.add(value);
            if (value.orderNo()!=null) headers.put(value.orderNo(),value);
            if (value.orderNo()!=null && row.get("recordId")!=null) headerRecords.put(row.get("recordId").toString(),value.orderNo());
        }
        var rawLines=raw.stream().filter(row->"FEISHU_SALES_ORDER_LINE".equals(row.get("tableCode"))).toList();
        var sourceLines=rawLines.stream().map(row->source(row,true,skuByOrder,headers,headerRecords,productRecords)).toList();
        var ambiguous=ambiguousRepairKeys(sourceLines,catalog);
        if (!ambiguous.isEmpty()) {
            // 同一条交易证据不能覆盖多条来源行；移除歧义组的人工目录后重新解析原始事实。
            var safeCatalog=catalog.stream().filter(f->!"OPERATOR_CONFIRMED".equals(f.associationEvidence()))
                    .filter(f->"SKU".equals(f.kind()) && f.orderNo()!=null)
                    .collect(java.util.stream.Collectors.groupingBy(Fact::orderNo));
            var safeLines=new ArrayList<>(sourceLines);
            for (int i=0;i<sourceLines.size();i++) if (ambiguous.contains(sourceLines.get(i).key())) {
                safeLines.set(i,source(rawLines.get(i),true,safeCatalog,headers,headerRecords,productRecords));
            }
            sourceLines=safeLines;
        }
        result.addAll(sourceLines);
        return result;
    }

    private static Set<String> ambiguousRepairKeys(List<Fact> sources,List<Fact> catalog) {
        Set<String> keys=new HashSet<>();
        sources.stream().collect(java.util.stream.Collectors.groupingBy(Fact::key)).values().stream()
                .filter(rows->rows.size()>1).forEach(rows->rows.forEach(f->keys.add(f.key())));
        for (Function<Fact,String> identity:List.<Function<Fact,String>>of(Fact::sourceProductId,Fact::sourceProductCode)) {
            sources.stream().filter(f->f.orderNo()!=null && identity.apply(f)!=null)
                    .collect(java.util.stream.Collectors.groupingBy(f->new ProductIdentity(f.orderNo(),identity.apply(f))))
                    .values().stream().filter(rows->rows.size()>1).forEach(rows->rows.forEach(f->keys.add(f.key())));
        }
        var sku=catalog.stream().filter(f->"SKU".equals(f.kind()) && f.orderNo()!=null).toList();
        for (var rows:sku.stream().collect(java.util.stream.Collectors.groupingBy(Fact::key)).values()) {
            if (rows.stream().map(Fact::systemLineId).distinct().count()>1) rows.forEach(f->keys.add(f.key()));
        }
        sku.stream().filter(f->f.systemVariantId()!=null)
                .collect(java.util.stream.Collectors.groupingBy(f->new ProductIdentity(f.orderNo(),f.systemVariantId())))
                .values().stream().filter(rows->rows.stream().map(Fact::systemLineId).distinct().count()>1)
                .forEach(rows->rows.forEach(f->keys.add(f.key())));
        sources.stream().filter(f->f.excludedRefund() || f.quantity()==null || f.quantity().signum()<=0)
                .forEach(f->keys.add(f.key()));
        return keys;
    }

    private record ProductIdentity(String orderNo,String productId) { }

    private Fact source(Map<String,Object> raw, boolean sku, Map<String,List<Fact>> catalog, Map<String,Fact> headers,
                        Map<String,String> headerRecords, Map<String,JsonNode> productRecords) {
        JsonNode fields=json.readTree(raw.get("valuesJson").toString());
        List<String> problems=new ArrayList<>();
        String rawNo=Objects.toString(raw.get("sourceDocumentNo"),null);
        String recordId=Objects.toString(raw.get("recordId"),null);
        String rowIdentity=recordId!=null?recordId:rawNo==null?raw.get("sheetName")+":"+raw.get("rowNumber"):rawNo;
        String no=orderNo(value(fields,"订单编号","订单编号门店","销售订单","关联订单","关联销售订单","来源订单号","订单号"));
        if (!sku && recordId==null) no=rawNo;
        if (sku && no==null && recordId!=null) no=headerRecords.get(linkedRecord(fields,"关联订单","销售订单","关联销售订单"));
        if (no==null) problems.add("来源订单关联缺失，未按名称或金额猜测订单");
        Fact parent=headers.get(no);
        String city=value(fields,"城市","所属城市","区域"), sales=value(fields,"销售","业务员","销售人员");
        String customer=value(fields,"门店","关联门店","客户名称","客户");
        Instant date=date(value(fields,"销售日期","下单时间","订单时间","创建时间"));
        if (sku && parent!=null) {
            if (city==null) city=parent.city();
            if (sales==null) sales=parent.sales();
            if (customer==null) customer=parent.customer();
            if (date==null) date=parent.orderDate();
        }
        if (date==null) problems.add("来源业务日期缺失或格式无法解析");
        if (city==null || sales==null || customer==null) problems.add("来源城市、责任销售或客户归属未完整提供");
        BigDecimal quantity=decimal(fields,problems,"数量","购买数量","数量(箱)","数量（箱）","销售数量");
        BigDecimal amount=decimal(fields,problems,"实际小计","明细金额","成交金额");
        if (amount==null) problems.add("来源实际小计缺失，未用收款或标价代替");
        BigDecimal paid=sku?null:decimal(fields,problems,"收款合计");
        BigDecimal unpaid=sku?null:decimal(fields,problems,"待付金额","待收金额","未收金额");
        if (!sku && (paid==null || unpaid==null)) problems.add("来源累计回款或待收字段缺失");
        String product=sku?value(fields,"订单产品","产品名称","商品名称","产品","商品","产品编号"):null;
        String specification=sku?value(fields,"规格","规格名称","规格描述","产品规格"):null;
        String descriptor=sku?value(fields,"产品编号"):null;
        String sourceProductId=sku?linkedRecord(fields,"产品编号","产品","商品"):null;
        JsonNode linkedProduct=sourceProductId==null?null:productRecords.get(sourceProductId);
        String sourceProductCode=sku?value(fields,"产品编码","商品编码"):null;
        if (linkedProduct!=null) {
            if (product==null) product=value(linkedProduct,"产品名称");
            if (specification==null) specification=value(linkedProduct,"规格");
            if (descriptor==null) descriptor=value(linkedProduct,"产品编码名称");
            if (sourceProductCode==null) sourceProductCode=value(linkedProduct,"产品编码","商品编码");
        }
        List<String> descriptorParts=descriptor==null?List.of():Arrays.stream(descriptor.split("\\s*[-－–—]\\s*"))
                .map(String::strip).filter(part->!part.isEmpty()).toList();
        String descriptorName=descriptorParts.size()<2?null:descriptorParts.size()==2?descriptorParts.getFirst():
                String.join("-",descriptorParts.subList(1,descriptorParts.size()-1));
        if (specification==null && descriptorParts.size()>=2) specification=descriptorParts.getLast();
        String unit=sku?value(fields,"单位","订货单位","单位名称","单位编码","unitCode"):null;
        String unitEvidence=unit==null?"UNKNOWN":"EXPLICIT";
        if (sku && unit==null && value(fields,"数量","购买数量")==null
                && value(fields,"数量(箱)","数量（箱）")!=null) {
            unit="箱";
            unitEvidence="COLUMN_INFERRED";
            problems.add("单位仅由数量(箱)列名推断，交易单位待确认，未自动换算");
        }
        boolean excluded=sku ? parent!=null && parent.excludedRefund() : quantity!=null && quantity.signum()==0;
        String key="ORDER|"+no;
        String associationEvidence=sku?"UNLINKED":"SOURCE_CODE";
        String confirmedUnit=null;
        if (sku) {
            String explicit=value(fields,"SKU编码","skuCode");
            String name=product, spec=specification;
            var orderCatalog=catalog.getOrDefault(no,List.of()).stream()
                    .filter(f->!"UNLINKED".equals(f.associationEvidence())).toList();
            String code=sourceProductCode;
            String orderNumber=no;
            var stable=orderCatalog.stream().filter(f->explicit!=null
                    ? f.key().equals("SKU|"+orderNumber+"|"+explicit)
                    : sourceProductId!=null && sourceProductId.equals(f.sourceProductId()))
                    .filter(f->"OPERATOR_CONFIRMED".equals(f.associationEvidence()) || spec==null || spec.equals(f.specification())).map(Fact::key).distinct().toList();
            String stableEvidence=explicit!=null?"SOURCE_CODE":"SOURCE_RECORD";
            if (stable.isEmpty() && explicit==null && code!=null) {
                stable=orderCatalog.stream().filter(f->code.equals(f.sourceProductCode()))
                        .filter(f->"OPERATOR_CONFIRMED".equals(f.associationEvidence()) || spec==null || spec.equals(f.specification())).map(Fact::key).distinct().toList();
                stableEvidence="SOURCE_CODE";
            }
            // 名称/规格只能帮助人工定位候选，不是来源关联已经正确的证据。
            var candidates=orderCatalog.stream()
                    .filter(f->((name!=null && name.equals(f.product())) || (descriptorName!=null && descriptorName.equals(f.product())))
                            && (spec==null || spec.equals(f.specification())))
                    .map(Fact::key).distinct().toList();
            if (stable.size()==1) {
                key=stable.getFirst();
                associationEvidence=stableEvidence;
                String stableKey=key;
                var confirmed=orderCatalog.stream().filter(f->stableKey.equals(f.key())
                        && "OPERATOR_CONFIRMED".equals(f.associationEvidence())).toList();
                if (!confirmed.isEmpty()) {
                    associationEvidence="OPERATOR_CONFIRMED";
                    var confirmedUnits=confirmed.stream().map(Fact::confirmedUnitCode).distinct().toList();
                    BigDecimal sourceQuantity=quantity;
                    if (!"EXPLICIT".equals(unitEvidence) && confirmedUnits.size()==1 && confirmedUnits.getFirst()!=null
                            && sourceQuantity!=null && sourceQuantity.signum()>0
                            && confirmed.stream().allMatch(f->f.quantity()!=null && sourceQuantity.compareTo(f.quantity())==0)) {
                        confirmedUnit=confirmedUnits.getFirst();
                        unitEvidence="OPERATOR_CONFIRMED";
                        problems.remove("单位仅由数量(箱)列名推断，交易单位待确认，未自动换算");
                    }
                }
            } else if (stable.isEmpty() && explicit==null && candidates.size()==1) {
                key=candidates.getFirst();
                associationEvidence="EXACT_NAME_SPEC";
                problems.add("商品名称/规格仅定位候选，尚缺可追溯的来源商品关联");
            }
            else {
                key="UNLINKED|"+no+"|"+rowIdentity;
                problems.add("SKU缺少唯一关联，仅展示来源明细，不判为业务漏导");
            }
            if (unit==null && confirmedUnit==null) problems.add("来源数量单位未提供，无法验证单位换算");
        }
        if (no==null) key="UNLINKED|"+raw.get("tableCode")+"|"+rowIdentity;
        String position=recordId==null?raw.get("sheetName")+":"+raw.get("rowNumber")+(rawNo==null?"":" / "+rawNo)
                :raw.get("sheetName")+" / "+recordId;
        Instant modified=date(value(fields,"修改时间","最后修改时间"));
        if (modified==null && recordId!=null) modified=date(Objects.toString(raw.get("lastModifiedTime"),null));
        return new Fact(key,no,sku?"SKU":"ORDER",city,sales,customer,product,specification,unit,date,
                amount,paid,unpaid,quantity,modified,position,excluded,List.copyOf(problems),unit,null,
                unitEvidence,associationEvidence,recordId,sourceProductId,sourceProductCode,null,null,confirmedUnit);
    }
    /** 关联只使用同一次采集的唯一 recordId 查找，不把 ID 当作可读名称。 */
    private static String linkedRecord(JsonNode fields,String... names) {
        for (String name:names) {
            JsonNode field=fields.get(name);
            if (field==null || !field.isObject()) continue;
            for (String key:List.of("link_record_ids","record_ids")) {
                JsonNode ids=field.get(key);
                if (ids!=null && ids.isArray() && ids.size()==1 && ids.get(0).isTextual() && !ids.get(0).asText().isBlank()) return ids.get(0).asText();
            }
        }
        return null;
    }
    private static String orderNo(String value) {
        if (value==null) return null;
        var matcher=ORDER.matcher(value);
        if (!matcher.find()) return null;
        String no=matcher.group();
        return matcher.find()?null:no;
    }
    private static String value(JsonNode fields,String... names) {
        for (String name:names) {
            String text=fieldText(fields.get(name),0);
            if (text!=null && !text.isBlank()) return text.strip();
        }
        return null;
    }
    // 仅接受可读标量及明确的显示值，不把关联 record_ids/open_id 转换成人或订单名称。
    private static String fieldText(JsonNode item,int depth) {
        if (item==null || item.isNull() || depth>20) return null;
        if (item.isTextual() || item.isNumber()) {
            String text=item.asText();
            return text.matches("(?i)rec[a-z0-9]{6,}")?null:text;
        }
        if (item.isArray()) {
            if (item.isEmpty()) return null;
            if (item.size()==1) return fieldText(item.get(0),depth+1);
            StringBuilder text=new StringBuilder();
            for (JsonNode part:item) {
                // 多个数值是多值 lookup，不是一个可证实的金额，禁止拼成另一个数字。
                if (part.isNumber() || part.isObject() && part.has("value") && !part.has("text") && !part.has("name")) return null;
                String segment=fieldText(part,depth+1);
                if (segment==null) return null;
                text.append(segment);
            }
            return text.toString();
        }
        if (item.isObject()) {
            for (String name:List.of("text","name","value")) {
                if (!item.has(name)) continue;
                String text=fieldText(item.get(name),depth+1);
                if (text!=null && !text.isBlank()) return text;
            }
        }
        return null;
    }
    private static BigDecimal decimal(JsonNode fields,List<String> issues,String... names) {
        String text=value(fields,names);
        if (text==null) return null;
        try { return new BigDecimal(text.replace(",","").replace("¥","").replace("￥","").replace("元","").strip()); }
        catch (NumberFormatException ex) { issues.add(names[0]+"不是可核对的数值"); return null; }
    }
    static Instant date(String value) {
        if (value==null) return null;
        try { return Instant.parse(value); } catch (DateTimeException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeException ignored) { }
        for (String pattern:List.of("uuuu-M-d","uuuu/M/d")) {
            try { return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).atStartOfDay(BUSINESS_ZONE).toInstant(); }
            catch (DateTimeException ignored) { }
        }
        for (String pattern:List.of("uuuu-M-d HH:mm:ss","uuuu/M/d HH:mm:ss","uuuu-M-d'T'HH:mm:ss","uuuu-M-d HH:mm")) {
            try { return LocalDateTime.parse(value,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).atZone(BUSINESS_ZONE).toInstant(); }
            catch (DateTimeException ignored) { }
        }
        try {
            BigDecimal serial=new BigDecimal(value);
            if (serial.compareTo(new BigDecimal("100000000000"))>=0 && serial.compareTo(new BigDecimal("9999999999999"))<=0) {
                return Instant.ofEpochMilli(serial.longValueExact());
            }
            if (serial.compareTo(new BigDecimal("20000"))>0 && serial.compareTo(new BigDecimal("80000"))<0) {
                long days=serial.longValue();
                long seconds=serial.subtract(BigDecimal.valueOf(days)).multiply(BigDecimal.valueOf(86400)).longValue();
                return LocalDate.of(1899,12,30).plusDays(days).atStartOfDay(BUSINESS_ZONE).plusSeconds(seconds).toInstant();
            }
        } catch (NumberFormatException | ArithmeticException ignored) { }
        return null;
    }
}
