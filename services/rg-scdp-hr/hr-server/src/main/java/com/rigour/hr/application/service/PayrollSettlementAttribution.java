package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollCollectionEvidenceView;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.hr.domain.payroll.SalarySchemeCalculator;
import com.rigour.order.api.v1.model.OrderPayrollSettlementView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PayrollSettlementAttribution {
    private static final Logger log = LoggerFactory.getLogger(PayrollSettlementAttribution.class);

    private final HrPayrollSource source;
    private final HrPayrollStore payroll;
    private final YearMonth effectiveMonth;

    public PayrollSettlementAttribution(
            HrPayrollSource source,
            HrPayrollStore payroll,
            @Value("${rigour.hr.payroll.settlement-owner-effective-month:2026-11}") String effectiveMonth) {
        this.source = source;
        this.payroll = payroll;
        this.effectiveMonth = YearMonth.parse(effectiveMonth);
    }

    public boolean applies(YearMonth month) {
        return !month.isBefore(effectiveMonth);
    }

    public Result calculate(String tenant, String employee, YearMonth month) {
        List<OrderPayrollSettlementView> rows;
        try {
            rows = source.payrollSettlements(tenant, employee, month.atDay(1), month.atEndOfMonth())
                    .stream()
                    .sorted(Comparator.comparing(
                            OrderPayrollSettlementView::orderId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (RuntimeException unavailable) {
            log.warn("Payroll settlement source unavailable for month {}", month, unavailable);
            return new Result(null, List.of(),
                    List.of("回款人整单付清来源暂不可用，不能按零业绩确认工资"), List.of());
        }
        var orderCounts = rows.stream().filter(row -> hasText(row.orderId()))
                .collect(Collectors.groupingBy(OrderPayrollSettlementView::orderId, Collectors.counting()));
        var sourceIds = rows.stream()
                .filter(row -> hasText(row.orderId()) && employee.equals(row.paymentOwnerCode()))
                .map(row -> "FULL:" + row.orderId()).distinct().toList();
        var lockedSources = sourceIds.isEmpty() ? Set.<String>of()
                : payroll.lockedSalesSettlementIds(tenant, employee, month.toString(), sourceIds);
        List<String> issues = new ArrayList<>();
        List<PayrollCollectionEvidenceView> evidence = new ArrayList<>();
        boolean complete = true;
        BigDecimal amount = BigDecimal.ZERO;
        for (var row : rows) {
            String order = Objects.toString(row.orderNo(), "未知订单");
            if ("PAYMENT_OWNER_PENDING".equals(row.qualityStatus())
                    || !hasText(row.paymentOwnerCode()) || !hasText(row.paymentOwnerEvidence())) {
                issues.add(order + "：首次收齐尾款的收款记录缺少回款人归属依据");
                complete = false;
                continue;
            }
            if (!employee.equals(row.paymentOwnerCode())
                    || !"COMPLETE".equals(row.qualityStatus())
                    || !hasText(row.orderId()) || orderCounts.get(row.orderId()) != 1
                    || !hasText(row.paymentId()) || row.fullyPaidDate() == null
                    || !month.equals(YearMonth.from(row.fullyPaidDate()))
                    || row.orderAmount() == null || row.orderAmount().signum() <= 0) {
                issues.add(order + "：首次付清业绩基数或归属待核定");
                complete = false;
                continue;
            }
            String sourceId = "FULL:" + row.orderId();
            if (lockedSources.contains(sourceId)) {
                issues.add(order + "：已计入确认或发放的薪酬，归属变更须核对原工资单");
                complete = false;
                continue;
            }
            if (row.refundedAmount() != null && row.refundedAmount().signum() > 0) {
                issues.add(order + "：退款对业绩及档位的影响需核定，不能按本月新标准扣减");
            }
            amount = amount.add(row.orderAmount());
            var lines = row.lines().stream()
                    .map(line -> new PayrollCollectionEvidenceView.ProductLine(
                            line.lineId(), line.productId(), line.productVariantId(), line.productName(),
                            line.unit(), line.quantity(), line.lineAmount()))
                    .toList();
            evidence.add(new PayrollCollectionEvidenceView(
                    sourceId, row.orderNo(), row.fullyPaidDate(), row.orderAmount(),
                    "FULLY_PAID", row.paymentId(), row.paymentNo(), row.paymentOwnerCode(),
                    row.paymentOwnerName(), row.paymentOwnerEvidence(), row.actualCashDate(),
                    row.refundedAmount(), lines));
        }
        return new Result(complete ? SalarySchemeCalculator.money(amount) : null,
                List.copyOf(evidence), issues.stream().distinct().toList(), rows);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Result(
            BigDecimal amount,
            List<PayrollCollectionEvidenceView> evidence,
            List<String> issues,
            List<OrderPayrollSettlementView> rows) {}
}
