package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.order.api.v1.OrderFulfillmentApi.WarehouseOption;
import com.rigour.order.api.v1.model.OrderFulfillmentQueueView;
import com.rigour.order.api.v1.model.OrderFulfillmentStatusView;
import com.rigour.order.api.v1.model.FulfillmentExecutionView;
import com.rigour.order.application.port.out.ErpFulfillmentClient;
import com.rigour.order.application.port.out.OrderFulfillmentStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 出库仓库按订单客户归属地区排序；没有匹配仓时保持稳定顺序供人工选择。 */
class OrderFulfillmentServiceRegionMatchTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void warehousesInOrderRegionComeFirst() {
        OrderFulfillmentService service = service("CUSAREA-SHENZHEN");

        var options = service.warehouses(7L);

        assertThat(options)
                .extracting(WarehouseOption::warehouseName)
                .containsExactly("深圳仓", "北京仓", "杭州仓");
        assertThat(options.getFirst().regionCode()).isEqualTo("CUSAREA-SHENZHEN");
    }

    @Test
    void warehousesKeepStableOrderWhenOrderHasNoRegion() {
        OrderFulfillmentService service = service(null);

        var options = service.warehouses(7L);

        assertThat(options)
                .extracting(WarehouseOption::warehouseName)
                .containsExactly("北京仓", "杭州仓", "深圳仓");
    }

    private static OrderFulfillmentService service(String orderRegion) {
        TestAuthorizationContext.set(caller("order:warehouse:select"));
        return new OrderFulfillmentService(new FakeStore(orderRegion), new FakeErpClient());
    }

    private static CallerIdentity caller(String... permissions) {
        return new CallerIdentity(
                "TENANT",
                USER_ID,
                TENANT_ID,
                USER_ID,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("order"),
                Set.of(permissions));
    }

    private static final class FakeErpClient implements ErpFulfillmentClient {
        @Override
        public List<WarehouseOption> warehouses(String tenant) {
            return List.of(
                    new WarehouseOption(11L, "北京仓", "CUSAREA-BEIJING"),
                    new WarehouseOption(12L, "杭州仓", "CUSAREA-HANGZHOU"),
                    new WarehouseOption(13L, "深圳仓", "CUSAREA-SHENZHEN"));
        }

        @Override
        public void requireWarehouse(String tenant, long warehouseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Receipt execute(CallerIdentity actor, String executionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<Receipt> receipt(String tenant, String executionId) {
            return java.util.Optional.empty();
        }
    }

    private static final class FakeStore implements OrderFulfillmentStore {
        private final String orderRegion;

        private FakeStore(String orderRegion) {
            this.orderRegion = orderRegion;
        }

        @Override
        public OrderFulfillmentQueueView queue(
                String tenant, int begin, int step, String keyword, String outboundStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderFulfillmentQueueView.Detail detail(String tenant, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String regionCode(String tenant, long id) {
            return orderRegion;
        }

        @Override
        public Set<Long> permittedWarehouseIds(
                CallerIdentity actor, long orderId, List<Long> candidates) {
            return Set.copyOf(candidates);
        }

        @Override
        public OrderFulfillmentStatusView status(String tenant, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderFulfillmentStatusView select(
                CallerIdentity actor, long id, long warehouse, int revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FulfillmentExecutionView prepare(
                CallerIdentity actor, long id, long warehouse, int revision, Instant time, String remark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FulfillmentExecutionView claim(CallerIdentity actor, String executionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderFulfillmentStatusView complete(
                String tenant, String executionId, long stockOutId, String stockOutNo, Instant time, long warehouseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void failed(String tenant, String executionId, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
