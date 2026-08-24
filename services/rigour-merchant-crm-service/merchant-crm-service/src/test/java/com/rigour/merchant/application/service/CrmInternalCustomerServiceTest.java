package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore.CustomerSearchCriteria;
import com.rigour.merchant.domain.enums.CrmCustomerStatus;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrmInternalCustomerServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb600-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb600-0000-7000-8000-000000000002");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createGeneratesCustomerCodeAndUsesDefaultActiveStatus() {
        FakeStore store = new FakeStore();
        CrmInternalCustomerService service = service(store);
        TestAuthorizationContext.set(caller("crm:customer:write"));

        InternalCustomerDetailView created = service.create(new InternalCustomerCommand(
                "  上海静安店  ", " 张三 ", " 13800000000 ", "east",
                "sales-1", " 李四 ", "a", " 上海市静安区 ", null, " 重点客户 ", null));

        assertThat(created.customerCode()).isEqualTo("CUS202608201234");
        assertThat(created.customerName()).isEqualTo("上海静安店");
        assertThat(created.regionCode()).isEqualTo("EAST");
        assertThat(created.settlementTypeCode()).isEqualTo("A");
        assertThat(created.statusCode()).isEqualTo(CrmCustomerStatus.ACTIVE.code());
        assertThat(created.revision()).isEqualTo(1);
    }

    @Test
    void listUsesIndependentSearchCriteria() {
        FakeStore store = new FakeStore();
        CrmInternalCustomerService service = service(store);
        TestAuthorizationContext.set(caller("crm:customer:read"));

        service.customers(0, 20, " CUS ", " 门店 ", " 138 ",
                "vip", "east", "sales-1", " RY202608220001 ", "active");

        assertThat(store.criteria.customerCode()).isEqualTo("CUS");
        assertThat(store.criteria.customerName()).isEqualTo("门店");
        assertThat(store.criteria.contactPhone()).isEqualTo("138");
        assertThat(store.criteria.customerTypeCode()).isEqualTo("VIP");
        assertThat(store.criteria.regionCode()).isEqualTo("EAST");
        assertThat(store.criteria.ownerSalesUserId()).isEqualTo("sales-1");
        assertThat(store.criteria.ownerStaffCode()).isEqualTo("RY202608220001");
        assertThat(store.criteria.statusCode()).isEqualTo(CrmCustomerStatus.ACTIVE.code());
    }

    @Test
    void updateRequiresRevision() {
        CrmInternalCustomerService service = service(new FakeStore());
        TestAuthorizationContext.set(caller("crm:customer:write"));

        assertThatThrownBy(() -> service.update(1L, new InternalCustomerCommand(
                "上海静安店", null, null, null, null, null,
                null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createRejectsUnsupportedCustomerStatus() {
        CrmInternalCustomerService service = service(new FakeStore());
        TestAuthorizationContext.set(caller("crm:customer:write"));

        assertThatThrownBy(() -> service.create(new InternalCustomerCommand(
                "上海静安店", null, null, null, null, null,
                null, null, "UNKNOWN", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void deleteUsesLogicalDeleteAndOptimisticRevision() {
        FakeStore store = new FakeStore();
        CrmInternalCustomerService service = service(store);
        TestAuthorizationContext.set(caller("crm:customer:write"));
        InternalCustomerDetailView created = service.create(new InternalCustomerCommand(
                "上海静安店", null, null, null, null, null,
                null, null, null, null, null));

        service.delete(created.id(), created.revision());

        assertThat(store.deleted).contains(created.id());
        TestAuthorizationContext.set(caller("crm:customer:read"));
        assertThatThrownBy(() -> service.customer(created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    private static CrmInternalCustomerService service(FakeStore store) {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
        return new CrmInternalCustomerService(store, generator);
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("crm"), Set.of(permission));
    }

    private static final class FakeStore implements CrmInternalCustomerStore {
        private final Map<Long, InternalCustomerDetailView> rows = new LinkedHashMap<>();
        private final Set<Long> deleted = new java.util.LinkedHashSet<>();
        private long nextId = 1;
        private CustomerSearchCriteria criteria;

        @Override
        public PageView<InternalCustomerSummaryView> customers(String tenantId, int begin, int step,
                                                               CustomerSearchCriteria criteria) {
            this.criteria = criteria;
            List<InternalCustomerSummaryView> items = rows.values().stream()
                    .filter(row -> !deleted.contains(row.id()))
                    .map(row -> new InternalCustomerSummaryView(row.id(), row.customerCode(),
                            row.customerName(), row.contactName(), row.contactPhone(), row.regionCode(),
                            row.ownerSalesUserId(), row.ownerSalesName(), row.settlementTypeCode(),
                            row.statusCode(), row.revision(), row.updatedTime()))
                    .toList();
            return new PageView<>(items.size(), begin, step, items);
        }

        @Override
        public Optional<InternalCustomerDetailView> customer(String tenantId, Long id) {
            if (deleted.contains(id)) return Optional.empty();
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean existsByCode(String tenantId, String customerCode) {
            return rows.values().stream().anyMatch(row -> row.customerCode().equals(customerCode));
        }

        @Override
        public InternalCustomerDetailView create(String tenantId, String customerCode,
                                                 InternalCustomerCommand command, String actorId) {
            Long id = nextId++;
            InternalCustomerDetailView row = new InternalCustomerDetailView(id, customerCode,
                    command.customerName(), command.contactName(), command.contactPhone(),
                    command.regionCode(), command.ownerSalesUserId(), command.ownerSalesName(),
                    command.settlementTypeCode(), command.address(), command.statusCode(),
                    command.remark(), 1, actorId, Instant.now(), actorId, Instant.now());
            rows.put(id, row);
            return row;
        }

        @Override
        public InternalCustomerDetailView update(String tenantId, Long id,
                                                 InternalCustomerCommand command, String actorId) {
            InternalCustomerDetailView current = rows.get(id);
            if (current == null || deleted.contains(id)) throw business(ErrorCode.NOT_FOUND);
            if (!current.revision().equals(command.revision())) throw business(ErrorCode.CONFLICT);
            InternalCustomerDetailView updated = new InternalCustomerDetailView(id, current.customerCode(),
                    command.customerName(), command.contactName(), command.contactPhone(),
                    command.regionCode(), command.ownerSalesUserId(), command.ownerSalesName(),
                    command.settlementTypeCode(), command.address(), command.statusCode(),
                    command.remark(), command.revision() + 1, current.createdBy(), current.createdTime(),
                    actorId, Instant.now());
            rows.put(id, updated);
            return updated;
        }

        @Override
        public void delete(String tenantId, Long id, int revision, String actorId) {
            InternalCustomerDetailView current = rows.get(id);
            if (current == null || deleted.contains(id)) throw business(ErrorCode.NOT_FOUND);
            if (current.revision() != revision) throw business(ErrorCode.CONFLICT);
            deleted.add(id);
        }

        private static BusinessException business(ErrorCode errorCode) {
            return new BusinessException(errorCode, errorCode.getMessage(), List.of());
        }
    }
}
