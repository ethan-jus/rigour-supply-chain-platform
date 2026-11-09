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

    @Transactional
    public LogisticsCostDetailView generate(String id, Generated request) {
        String tenant = tenant();
        var row = store.lock(tenant, actor(), id);
        if (row.costId() != null) return costs.entry("logistics", row.costId());
        if (request == null || request.revision() != row.revision() || request.command() == null)
            throw bad("导入版本已变化，请刷新");
        var c = request.command();
        var p = c.ledger();
        var bill = row.order();
        if (p == null
                || !"DELIVERY".equals(p.transportPurposeCode())
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
                                        !"SALES_ORDER".equals(s.orderTypeCode())
                                                || !"COST".equals(s.linkRoleCode())))
            throw bad("请先关联本系统已有销售单");
        if (p.feeItems() == null
                || p.feeItems().size() != 1
                || !"COMPANY".equals(p.feeItems().getFirst().bearerCode())
                || p.feeItems().getFirst().ourAmount() == null
                || p.feeItems().getFirst().feeAmount() == null
                || bill.paidAmount().compareTo(p.feeItems().getFirst().ourAmount()) != 0
                || bill.paidAmount().compareTo(p.feeItems().getFirst().feeAmount()) != 0)
            throw bad("导入费用应为原报表实付金额，额外费用不重复叠加");
        if (!Set.of("已完成", "司机完成", "已支付").contains(bill.status()))
            throw bad("此货拉拉订单尚未完成或已取消，请先核对原始账单");
        if (store.trackingExists(tenant, bill.orderNo())) throw bad("该货拉拉单号已有运输记录，请到列表查看，避免重复登记");
        var saved = costs.save("logistics", null, c);
        store.generated(tenant, id, row.revision(), saved.id());
        return saved;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
