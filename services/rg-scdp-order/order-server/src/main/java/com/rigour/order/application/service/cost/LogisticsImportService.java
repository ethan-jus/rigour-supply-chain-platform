package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.port.out.LogisticsImportStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class LogisticsImportService {
    private final LogisticsImportStore store;
    private final OrderLogisticsCostService costs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.order.application.port.out.PlatformTransportStore platform;

    public LogisticsImportService(LogisticsImportStore store, OrderLogisticsCostService costs) {
        this.store = store;
        this.costs = costs;
    }

    private static String tenant() {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null)
            throw new com.rigour.shared.context.AuthorizationDeniedException(
                    "order:logistics-cost:write");
        return caller.tenantId().toString();
    }

    private static String actor() {
        return AuthorizationContext.requireCurrent().principalId().toString();
    }

    @Transactional
    public List<Row> stage(Upload upload) {
        String tenant = tenant();
        if (upload == null
                || upload.rows() == null
                || upload.rows().isEmpty()
                || upload.rows().size() > 200
                || upload.fileName() == null
                || upload.fileName().isBlank()
                || upload.fileName().length() > 200
                || upload.fileHash() == null
                || !upload.fileHash().matches("[a-f0-9]{64}"))
            throw bad("一次请上传1～200条有效订单，文件名及校验值不能为空");
        Set<String> keys = new HashSet<>();
        for (var row : upload.rows()) {
            if (row != null && row.facts() != null) throw bad("Excel导入不能声明为平台接口账单");
            if (row == null
                    || row.enterpriseId() != null
                            && !row.enterpriseId().isBlank()
                            && !row.enterpriseId().matches("[A-Za-z0-9_-]{1,80}")
                    || row.orderNo() == null
                    || !row.orderNo().matches("[A-Za-z0-9_.-]{6,80}")
                    || row.orderNo().matches(".*[eE][+-]?[0-9]+$")
                    || !keys.add(row.orderNo())) throw bad("订单号无效或重复；订单号必须为完整文本，企业ID如填写须有效");
            if (row.paidAmount() == null
                    || row.paidAmount().signum() < 0
                    || row.paidAmount().scale() > 2
                    || row.paidAmount().compareTo(new java.math.BigDecimal("999999999999.99")) > 0
                    || row.status() == null
                    || row.status().isBlank()
                    || row.status().length() > 50
                    || row.useTime() == null
                    || !row.useTime()
                            .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}( [0-9]{2}:[0-9]{2}(:[0-9]{2})?)?"))
                throw bad("实付金额、用车时间或订单状态无效");
            try {
                LocalDate.parse(row.useTime().substring(0, 10));
                if (row.useTime().length() > 10)
                    java.time.LocalTime.parse(row.useTime().substring(11));
            } catch (Exception e) {
                throw bad("用车日期无效");
            }
            if (row.salesOrderNos() != null
                    && (row.salesOrderNos().size() > 50
                            || row.salesOrderNos().stream()
                                    .anyMatch(
                                            code ->
                                                    code == null
                                                            || code.isBlank()
                                                            || code.length() > 100)))
                throw bad("销售单号最多50个，每个不超过100字");
            if (row.raw() == null
                    || row.raw().size() > 100
                    || row.raw().entrySet().stream()
                            .anyMatch(
                                    e ->
                                            e.getKey() == null
                                                    || e.getKey().length() > 100
                                                    || e.getValue() == null
                                                    || e.getValue().length() > 2000))
                throw bad("原始报表字段超出限制");
        }
        return store.stage(tenant, actor(), upload);
    }

    public List<Row> rows(int begin) {
        if (begin < 0 || begin > 100000) throw bad("分页参数无效");
        return store.rows(tenant(), actor(), begin);
    }

    private static boolean administrator() {
        var p = AuthorizationContext.requireCurrent().permissions();
        return p.contains("*:*:*") || p.contains("order:logistics-cost:manage-imports");
    }

    public Row byCost(String id) {
        var cost = costs.entry("logistics", id);
        return store.byCost(AuthorizationContext.requireCurrent().tenantId().toString(), cost.id())
                .orElse(null);
    }

    public List<Row> query(SourceQuery q) {
        if (q == null
                || q.begin() < 0
                || q.begin() > 100000
                || q.orderNo() != null && q.orderNo().length() > 80
                || q.state() != null
                        && !q.state().isBlank()
                        && !Set.of("UNMATCHED", "GENERATED").contains(q.state()))
            throw bad("来源筛选条件无效");
        return store.query(tenant(), actor(), administrator(), q);
    }

    @Transactional
    public Row receive(ApiSource source) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() == null)
            throw new com.rigour.shared.context.AuthorizationDeniedException("integration-service");
        AuthorizationContext.requirePermission("integration:huolala:sync");
        if (source == null
                || source.order() == null
                || source.order().facts() == null
                || source.connectorId() == null
                || !source.connectorId().equals(source.order().facts().connectorId())
                || source.payloadHash() == null
                || !source.payloadHash().matches("[a-f0-9]{64}")) throw bad("平台来源凭据不完整");
        var o = source.order();
        if (o.enterpriseId() == null
                || !o.enterpriseId().matches("[A-Za-z0-9_-]{1,80}")
                || o.orderNo() == null
                || !o.orderNo().matches("[A-Za-z0-9_.-]{6,80}")
                || o.useTime() == null
                || o.useTime().length() < 10
                || o.raw() == null) throw bad("平台订单标识或日期无效");
        LocalDate.parse(o.useTime().substring(0, 10));
        var bill = o.facts().billAmount();
        if (bill != null && (bill.signum() < 0 || bill.scale() > 2 || bill.precision() > 18))
            throw bad("平台账单金额无效");
        String t = caller.tenantId().toString(), a = caller.principalId().toString();
        if (platform != null) platform.lockTenant(t);
        var received = store.receive(t, a, source);
        return platform == null ? received : platform.materialize(t, a, received);
    }

    @Transactional
    public void voucher(VoucherUpdate update) {
        var caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() == null)
            throw new com.rigour.shared.context.AuthorizationDeniedException("integration-service");
        AuthorizationContext.requirePermission("integration:huolala:sync");
        platform.voucher(caller.tenantId().toString(), update);
    }

    @Transactional
    public Row verifyBill(String id, VerifyBill command) {
        String t = tenant();
        if (!administrator())
            throw new com.rigour.shared.context.AuthorizationDeniedException(
                    "order:logistics-cost:manage-imports");
        if (command == null
                || command.amount() == null
                || command.amount().signum() < 0
                || command.amount().scale() > 2
                || command.amount().precision() > 14
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500) throw bad("请填写非负净费用（两位小数）和账单核对依据");
        var row = store.lockManaged(t, actor(), true, id);
        if ("API".equals(row.sourceChannel())) throw bad("平台账单只读，不能手动修改平台金额");
        if (row.revision() != command.revision()) throw bad("来源已变化，请刷新后核对");
        if (row.costId() != null
                && !"DRAFT".equals(costs.entry("logistics", row.costId()).statusCode()))
            throw bad("请先撤销已确认费用，再核对净费用");
        store.verifyBill(
                t,
                id,
                row.revision(),
                new BillVerification(
                        command.amount(),
                        command.reason().strip(),
                        actor(),
                        java.time.Instant.now()));
        return store.lockManaged(t, actor(), true, id);
    }

    @Transactional
    public Row accept(String id, AcceptSource command) {
        String t = tenant();
        if (!administrator())
            throw new com.rigour.shared.context.AuthorizationDeniedException(
                    "order:logistics-cost:manage-imports");
        if (command == null
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500) throw bad("请填写来源差异核对说明");
        var row = store.lockManaged(t, actor(), true, id);
        if (!row.sourceDifference()
                || row.latestOrder() == null
                || row.revision() != command.revision()) throw bad("来源信息已变化，请刷新");
        if (row.costId() != null) {
            var cost = costs.entry("logistics", row.costId());
            if (!"DRAFT".equals(cost.statusCode())) throw bad("请先撤销已确认费用，再核对来源差异");
            var f = row.latestOrder().facts();
            var amount =
                    row.verification() != null
                            ? row.verification().amount()
                            : f == null ? null : f.billAmount();
            if (f == null
                    || row.verification() == null && !"FINAL".equals(f.billState())
                    || amount == null
                    || cost.data().amount() == null
                    || amount.compareTo(cost.data().amount()) != 0)
                throw bad("请先将待确认费用核对为最新净账单金额，再接受来源变更");
        }
        store.accept(t, id, command.revision(), actor(), command.reason().strip());
        return store.lockManaged(t, actor(), true, id);
    }

    @Transactional
    public LogisticsCostDetailView generate(String id, Generated request) {
        String tenant = tenant();
        var row = store.lockManaged(tenant, actor(), administrator(), id);
        if (row.costId() != null) return costs.entry("logistics", row.costId());
        if (request == null || request.revision() != row.revision() || request.command() == null)
            throw bad("导入版本已变化，请刷新");
        var c = request.command();
        var p = c.ledger();
        var bill = row.order();
        var expectedTypes =
                Map.of(
                        "DELIVERY",
                        "SALES_ORDER",
                        "TRANSFER",
                        "TRANSFER_ORDER",
                        "OTHER",
                        "OTHER_OUT");
        if (p == null
                || !expectedTypes.containsKey(p.transportPurposeCode())
                || !"THIRD_PARTY".equals(p.deliveryModeCode())
                || !"HLL".equals(p.carrierCode())
                || !bill.orderNo().equals(p.trackingNo())
                || !LocalDate.parse(bill.useTime().substring(0, 10)).equals(c.costDate()))
            throw bad("运输类型、物流公司、货拉拉单号及日期须与导入报表一致");
        if (p.sources() == null
                || p.sources().isEmpty()
                || p.sources().stream()
                        .anyMatch(
                                s ->
                                        !expectedTypes
                                                        .get(p.transportPurposeCode())
                                                        .equals(s.orderTypeCode())
                                                || !"COST".equals(s.linkRoleCode())))
            throw bad("请关联与运输类型对应的已有业务单据");
        java.math.BigDecimal expected =
                row.verification() != null
                        ? row.verification().amount()
                        : bill.facts() == null ? bill.paidAmount() : bill.facts().billAmount();
        if (bill.facts() != null
                && (row.verification() == null && !"FINAL".equals(bill.facts().billState())
                        || expected == null
                        || row.sourceDifference())) throw bad("平台账单尚未明确或存在差异，请先核对来源");
        if (p.feeItems() == null
                || p.feeItems().size() != 1
                || !"COMPANY".equals(p.feeItems().getFirst().bearerCode())
                || p.feeItems().getFirst().ourAmount() == null
                || p.feeItems().getFirst().feeAmount() == null
                || expected == null
                || expected.compareTo(p.feeItems().getFirst().ourAmount()) != 0
                || expected.compareTo(p.feeItems().getFirst().feeAmount()) != 0)
            throw bad("运输费用应与已核对来源金额一致，不能重复叠加费用");
        if (bill.facts() == null
                && row.verification() == null
                && !Set.of("已完成", "司机完成", "已支付").contains(bill.status()))
            throw bad("此货拉拉订单尚未完成或已取消，请先核对原始账单");
        if (bill.facts() == null
                && row.verification() == null
                && bill.raw().getOrDefault("支付方式", "").contains("月结"))
            throw bad("月结报表的实付金额不能直接作为成本，请先核对最终净费用");
        if (store.trackingExists(tenant, bill.orderNo())) throw bad("该货拉拉单号已有运输记录，请到列表查看，避免重复登记");
        var saved = costs.save("logistics", null, c);
        store.generated(tenant, id, row.revision(), saved.id());
        return saved;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
