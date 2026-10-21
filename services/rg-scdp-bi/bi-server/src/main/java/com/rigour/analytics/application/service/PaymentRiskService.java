package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.PaymentRiskView;
import com.rigour.analytics.api.v1.model.PaymentRiskView.*;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.PaymentRiskStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentRiskService {
    public static final int MAX_LINES = 50_000;
    private static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "SEVERE", "UNKNOWN");
    private final PaymentRiskStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;

    public PaymentRiskService(PaymentRiskStore store, BiDataScopeService scopes, Clock analyticsClock) {
        this.store = store;
        this.scopes = scopes;
        this.clock = analyticsClock;
    }

    @Transactional(readOnly = true)
    public PaymentRiskView query(Instant from, Instant to, String region, String owner,
                                 List<Long> categoryIds, String riskLevel) {
        var caller = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        if (from == null || to == null || from.isAfter(to)) throw bad("请提供有效订单日期范围");
        if (riskLevel != null && !LEVELS.contains(riskLevel)) throw bad("风险等级无效");
        if (categoryIds != null && categoryIds.stream().anyMatch(id -> id == null || id <= 0))
            throw bad("产品分类标识必须大于0");
        var scope = scopes.resolve(region, owner);
        String tenant = caller.tenantId().toString();
        var generatedAt = clock.instant();
        var categories = store.categories(tenant);
        var catalog = categories.stream().collect(Collectors.toMap(Category::id, Function.identity()));
        Set<String> selected = categoryIds == null ? Set.of() :
                categoryIds.stream().map(String::valueOf).collect(Collectors.toSet());
        if (!catalog.keySet().containsAll(selected)) throw bad("所选分类不存在或尚未同步");
        Instant end = to.isAfter(generatedAt) ? generatedAt : to;
        var facts = from.isAfter(end) ? List.<PaymentRiskStore.Fact>of()
                : store.load(tenant, from, end, scope.regionCode(), scope.ownerStaffCode(), MAX_LINES + 1);
        if (facts.size() > MAX_LINES) throw bad("未回款明细超过50000行，请缩小订单日期或城市范围；未返回部分统计");
        var today = generatedAt.atZone(BiBusinessTime.ZONE).toLocalDate();
        var grouped = facts.stream().collect(Collectors.groupingBy(PaymentRiskStore.Fact::orderId,
                LinkedHashMap::new, Collectors.toList()));
        List<Order> orders = new ArrayList<>();
        for (var orderFacts : grouped.values()) {
            var first = orderFacts.getFirst();
            var date = first.orderDate().atZone(BiBusinessTime.ZONE).toLocalDate();
            long days = ChronoUnit.DAYS.between(date, today);
            Map<String, Product> products = new LinkedHashMap<>();
            for (var f : orderFacts) {
                var ancestors = ancestors(f.categoryId(), catalog);
                var category = catalog.get(f.categoryId());
                String name = category == null ? f.categoryName() : category.name();
                boolean cloth = "台呢".equals(name) || ancestors.stream()
                        .map(catalog::get).filter(Objects::nonNull).anyMatch(c -> "台呢".equals(c.name()));
                boolean known = f.categoryId() != null && name != null && !name.isBlank();
                String level = classify(days, cloth, known);
                String key = f.productId() == null ? "unknown:" + f.categoryId() + ":" + f.productName() : f.productId();
                var previous = products.get(key);
                BigDecimal amount = f.lineAmount() == null ? BigDecimal.ZERO : f.lineAmount();
                if (previous != null) amount = amount.add(previous.productAmount());
                products.put(key, new Product(f.categoryId(), name, f.productId(), f.productName(), level, amount));
            }
            // 先在完整订单内唯一归属，再筛选；切换分类不能让同单换归属或降低风险。
            var assigned = products.values().stream().max(Comparator
                    .comparingInt((Product p) -> severity(p.riskLevel()))
                    .thenComparing(Product::productAmount)
                    .thenComparing(p -> Objects.toString(p.productId(), ""), Comparator.reverseOrder())
                    .thenComparing(p -> Objects.toString(p.productName(), ""), Comparator.reverseOrder())).orElseThrow();
            if (!selected.isEmpty() && Collections.disjoint(selected, ancestors(assigned.categoryId(), catalog))) continue;
            if (riskLevel != null && !riskLevel.equals(assigned.riskLevel())) continue;
            orders.add(new Order(first.orderId(), first.orderNo(), first.customerName(),
                    first.regionCode(), first.regionName(), first.ownerStaffCode(), first.ownerStaffName(),
                    date, days, first.orderAmount(), first.paidAmount(), List.of(assigned)));
        }
        Instant updated = facts.stream().map(PaymentRiskStore.Fact::syncedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        return new PaymentRiskView(generatedAt, updated, categories, List.copyOf(orders));
    }

    private static int severity(String level) {
        return switch (level) {
            case "SEVERE" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    static String classify(long days, boolean cloth, boolean known) {
        if (!known || days < 0) return "UNKNOWN";
        int interval = cloth ? 10 : 30;
        return days <= interval ? "LOW" : days <= interval * 2L ? "MEDIUM"
                : days <= interval * 3L ? "HIGH" : "SEVERE";
    }

    private static Set<String> ancestors(String id, Map<String, Category> catalog) {
        Set<String> ids = new HashSet<>();
        while (id != null && ids.add(id)) {
            var c = catalog.get(id);
            id = c == null ? null : c.parentId();
        }
        return ids;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
