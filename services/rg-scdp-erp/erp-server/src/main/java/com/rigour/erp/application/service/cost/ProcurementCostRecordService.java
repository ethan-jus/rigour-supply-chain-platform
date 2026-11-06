package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class ProcurementCostRecordService {
    public static final String FREIGHT_TYPE = "PROCUREMENT_FREIGHT";
    private final ProcurementCostRecords records;
    private final ErpCostStore costs;
    private final CostReferenceSource references;

    public ProcurementCostRecordService(
            ProcurementCostRecords records, ErpCostStore costs, CostReferenceSource references) {
        this.records = records;
        this.costs = costs;
        this.references = references;
    }

    public MasterDataPageView<ProcurementCostRecordView> page(
            int begin,
            int step,
            String number,
            String supplier,
            String status,
            LocalDate from,
            LocalDate to) {
        String tenant = tenant("read");
        if (begin < 0
                || step < 1
                || step > 200
                || number != null && number.length() > 50
                || supplier != null && supplier.length() > 200
                || status != null && !Set.of("PENDING", "CONFIRMED", "VOID").contains(status)
                || from != null && to != null && to.isBefore(from)) throw bad("采购成本筛选条件无效");
        return records.page(
                tenant, begin, step, number, supplier, status, from, to, "erp:cost:purchase:read");
    }

    public ProcurementCostRecordView detail(String code) {
        code(code);
        return records.record(tenant("read"), code, "erp:cost:purchase:read", false);
    }

    public List<ErpCostAuditView> history(String code) {
        code(code);
        String tenant = tenant("read");
        String permission = "erp:cost:purchase:read";
        var row = records.record(tenant, code, permission, false);
        var result = new ArrayList<ErpCostAuditView>();
        for (String id : records.freightRecordIds(tenant, row.orderId())) {
            result.addAll(costs.history(tenant, id));
        }
        result.sort(Comparator.comparing(ErpCostAuditView::occurredTime).reversed());
        return List.copyOf(result);
    }

    @Transactional
    public ProcurementCostRecordView confirmFreight(String code, ProcurementFreightConfirmation c) {
        return saveFreight(code, c, true);
    }

    @Transactional
    public ProcurementCostRecordView draftFreight(String code, ProcurementFreightConfirmation c) {
        return saveFreight(code, c, false);
    }

    private ProcurementCostRecordView saveFreight(
            String code, ProcurementFreightConfirmation c, boolean confirm) {
        code(code);
        String tenant = tenant(confirm ? "confirm" : "write");
        AuthorizationContext.requirePermission("erp:cost:purchase:write");
        String permission = "erp:cost:purchase:" + (confirm ? "confirm" : "write");
        if (c == null
                || c.requestId() == null
                || !c.requestId().matches("[A-Za-z0-9_-]{1,36}")
                || c.orderRevision() < 1
                || c.costDate() == null
                || c.actualAmount() == null
                || c.actualAmount().signum() < 0
                || c.actualAmount().scale() > 2
                || c.actualAmount().compareTo(new java.math.BigDecimal("9999999999999999.99")) > 0
                || !Set.of("PAID", "UNPAID", "UNKNOWN")
                        .contains(Objects.toString(c.paymentStatusCode(), ""))
                || c.reason() == null
                || c.reason().isBlank()
                || c.reason().length() > 500) throw bad("请填写实际运费、发生日期、付款状态和核实说明，金额最多两位小数且不得为负");
        var row = records.record(tenant, code, permission, true);
        var prior =
                row.freightRecordId() == null || "VOID".equals(row.freightRecordStatus())
                        ? null
                        : costs.entry(tenant, "PURCHASE", row.freightRecordId(), permission);
        // PO 锁串行化同单核实；网络重试返回相同回执，不能再追加一笔实际运费。
        if (prior != null && c.requestId().equals(prior.data().requestId())) {
            if (row.orderRevision() == c.orderRevision()
                    && (confirm ? "CONFIRMED" : "DRAFT").equals(prior.statusCode())
                    && c.actualAmount().compareTo(prior.data().amount()) == 0
                    && c.costDate().equals(prior.data().costDate())
                    && c.paymentStatusCode().equals(prior.data().paymentStatusCode())
                    && c.reason().equals(prior.data().reason())) return row;
            throw new BusinessException(ErrorCode.CONFLICT, "请求编号已用于其他核实内容，请刷新后重试", List.of());
        }
        if (row.freightRecordStatus() != null && !"DRAFT".equals(row.freightRecordStatus()))
            throw bad("只有待确认运费可编辑或确认，已确认运费请先撤销；已作废运费请先删除");
        if (row.orderRevision() != c.orderRevision()
                || !Objects.equals(row.freightRevision(), c.freightRevision())
                || !Objects.equals(row.freightRecordId(), c.freightRecordId()))
            throw new BusinessException(ErrorCode.CONFLICT, "采购单或实际运费已变化，请刷新后重新核实", List.of());
        var sources = costs.sources(tenant, "PURCHASE", "", List.of(row.orderId()), permission);
        if (sources.size() != 1) throw bad("采购来源不存在、已取消或无权限");
        var source = sources.getFirst();
        if (source.cityCode() == null || source.cityCode().isBlank())
            throw bad("收货仓库未维护业务归属地区，请在仓库信息补齐后核实运费");
        String cityName = references.city(source.cityCode(), permission);
        var goods =
                new ErpCostGoodsDetails(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        row.supplierName(),
                        source.targetWarehouseId() == null
                                ? source.warehouseId()
                                : source.targetWarehouseId(),
                        row.warehouseName(),
                        row.orderId(),
                        null,
                        null,
                        null);
        var command =
                new ErpCostCommand(
                        c.requestId(),
                        prior == null ? null : row.freightRevision(),
                        "采购实际运费-" + code,
                        FREIGHT_TYPE,
                        source.cityCode(),
                        cityName,
                        null,
                        null,
                        c.costDate(),
                        c.actualAmount(),
                        c.paymentStatusCode(),
                        null,
                        "实际运费经业务核实计入采购成本；约定运费仅供对照。",
                        c.reason(),
                        goods,
                        null,
                        null,
                        row.supplierName());
        var saved =
                costs.save(
                        tenant,
                        "PURCHASE",
                        prior == null ? null : row.freightRecordId(),
                        command,
                        sources,
                        row.warehouseName(),
                        null,
                        AuthorizationContext.requireCurrent().principalId().toString(),
                        permission);
        if (confirm && !"CONFIRMED".equals(saved.statusCode()))
            costs.transition(
                    tenant,
                    "PURCHASE",
                    saved.id(),
                    new ErpCostActionCommand(saved.revision(), c.reason()),
                    AuthorizationContext.requireCurrent().principalId().toString(),
                    permission);
        return records.record(tenant, code, permission, false);
    }

    private static String tenant(String action) {
        AuthorizationContext.requirePermission("erp:cost:purchase:" + action);
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null)
            throw new AuthorizationDeniedException("erp:cost:purchase:" + action);
        return actor.tenantId().toString();
    }

    private static void code(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{1,50}")) throw bad("采购单号无效");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
