package com.rigour.integration.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订货宝出站端口。
 *
 * <p>该端口只描述 Integration 需要的供应商能力，供应商的 HTTP、认证、重试、限流和
 * 字段兼容逻辑必须留在 infrastructure。所有请求都带租户和连接器标识，避免把一个
 * 供应商连接误用到另一个租户。</p>
 */
public interface DinghuobaoClient {

    ConnectionTestResult testConnection(Connector connector);

    Page<Product> getProducts(Connector connector, ProductQuery query);

    Page<Customer> getCustomers(Connector connector, CustomerQuery query);

    Page<OrderSummary> getOrders(Connector connector, OrderQuery query);

    OrderDetail getOrderContent(Connector connector, String orderNumber,
                                boolean autoMarkDownloaded, boolean autoAudit);

    /** 外部连接配置；secretRef 只能是 Secret 引用，不允许放密码或 API Key。 */
    record Connector(UUID tenantId, UUID connectorId, String baseUrl, String secretRef) {
        public Connector {
            if (tenantId == null || connectorId == null) {
                throw new IllegalArgumentException("tenantId and connectorId are required");
            }
        }
    }

    /** 订货宝的 begin/step 偏移分页。step 的上限由订货宝文档规定为 1000。 */
    record PageRequest(int begin, int step) {
        public PageRequest {
            if (begin < 0) {
                throw new IllegalArgumentException("begin must be >= 0");
            }
            if (step < 1 || step > 1000) {
                throw new IllegalArgumentException("step must be between 1 and 1000");
            }
        }

        public static PageRequest first(int step) {
            return new PageRequest(0, step);
        }
    }

    record Page<T>(PageRequest request, long total, List<T> items) {
        public Page {
            if (request == null || items == null) {
                throw new IllegalArgumentException("request and items are required");
            }
            items = List.copyOf(items);
        }

        public boolean hasNext() {
            return !items.isEmpty() && (total >= 0
                    ? request.begin() + items.size() < total
                    : items.size() == request.step());
        }

        public PageRequest nextRequest() {
            if (!hasNext()) {
                throw new IllegalStateException("page has no next page");
            }
            return new PageRequest(request.begin() + items.size(), request.step());
        }
    }

    /** 创建/更新时间窗口；订货宝接口要求东八区的 YYYY-MM-DD HH:mm:ss 文本。 */
    record TimeWindow(Instant from, Instant to) {
        public TimeWindow {
            if (from == null || to == null || !from.isBefore(to)) {
                throw new IllegalArgumentException("time window must be ordered and non-empty");
            }
        }
    }

    record ProductQuery(PageRequest page, String status, String putaway, String goodsCode) {
        public ProductQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static ProductQuery first(int step) {
            return new ProductQuery(PageRequest.first(step), null, null, null);
        }
    }

    record CustomerQuery(PageRequest page, Integer status, Integer dataType,
                         String timeType, TimeWindow window) {
        public CustomerQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
            if (timeType != null && !timeType.equals("create_date") && !timeType.equals("update_date")) {
                throw new IllegalArgumentException("timeType must be create_date or update_date");
            }
            if (window != null && timeType == null) {
                throw new IllegalArgumentException("timeType is required with a time window");
            }
        }

        public static CustomerQuery first(int step, String timeType, TimeWindow window) {
            return new CustomerQuery(PageRequest.first(step), null, null, timeType, window);
        }
    }

    record OrderQuery(PageRequest page, String orderStatus, TimeWindow createdWindow,
                      TimeWindow updatedWindow, String exceptionStatus, String payStatus) {
        public OrderQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static OrderQuery first(int step, TimeWindow createdWindow, TimeWindow updatedWindow) {
            return new OrderQuery(PageRequest.first(step), null, createdWindow, updatedWindow, null, null);
        }
    }

    record Product(String sourceId, String code, String name, String putaway,
                   Map<String, Object> attributes) {
        public Product {
            attributes = immutableAttributes(attributes);
        }
    }

    record Customer(String sourceId, String account, String number, String name,
                    String status, Instant createdAt, Instant updatedAt,
                    Map<String, Object> attributes) {
        public Customer {
            attributes = immutableAttributes(attributes);
        }
    }

    record OrderSummary(String sourceId, String orderNumber, String status,
                        BigDecimal amount, Instant createdAt, Instant updatedAt,
                        String customerNumber, String paymentStatus,
                        Map<String, Object> attributes) {
        public OrderSummary {
            attributes = immutableAttributes(attributes);
        }
    }

    record OrderDetail(String orderNumber, String status, BigDecimal amount,
                       Map<String, Object> attributes) {
        public OrderDetail {
            attributes = immutableAttributes(attributes);
        }
    }

    record ConnectionTestResult(boolean success, String code, String message,
                                Instant tokenExpiresAt) {
        public static ConnectionTestResult success(Instant tokenExpiresAt) {
            return new ConnectionTestResult(true, "OK", "订货宝认证成功", tokenExpiresAt);
        }

        public static ConnectionTestResult failure(String code, String message) {
            return new ConnectionTestResult(false, code, message, null);
        }
    }

    private static Map<String, Object> immutableAttributes(Map<String, Object> attributes) {
        return attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
