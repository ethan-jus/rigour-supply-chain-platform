package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.application.port.out.HrEmployeeStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HrEmployeeServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb800-1000-7000-8000-000000000001");
    private static final UUID SERVICE_ID = UUID.fromString("019fb800-1000-7000-8000-000000000002");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void syncExternalEmployeesUsesSystemAuditActorForServiceCaller() {
        FakeStore store = new FakeStore();
        HrEmployeeService service = new HrEmployeeService(store, fixedGenerator());
        TestAuthorizationContext.set(serviceCaller("hr:employee:sync"));

        service.syncExternalEmployees(new ExternalEmployeeSyncCommand(" feishu ", List.of(
                new ExternalEmployeeRowCommand(null, " default ", " emp-1 ", null,
                        " 李嘉豪 ", " 销售 ", " 销售员 ", null, null,
                        " 华北地区 ", " 西安 ", null, null, " 在职 ",
                        null, null, Instant.parse("2026-09-01T00:00:00Z"),
                        null, "hash-1", "{}"))));

        assertThat(store.syncTenantId).isEqualTo(TENANT_ID.toString());
        assertThat(store.syncSourceSystem).isEqualTo("FEISHU");
        assertThat(store.syncActorId).isEqualTo("SYSTEM");
        assertThat(store.syncRows).singleElement().satisfies(row -> {
            assertThat(row.sourceTenantKey()).isEqualTo("default");
            assertThat(row.sourceEmployeeId()).isEqualTo("emp-1");
            assertThat(row.employeeName()).isEqualTo("李嘉豪");
        });
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static CallerIdentity serviceCaller(String permission) {
        return new CallerIdentity("SERVICE", SERVICE_ID, TENANT_ID, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("hr"), Set.of(permission));
    }

    private static final class FakeStore implements HrEmployeeStore {
        private String syncTenantId;
        private String syncSourceSystem;
        private String syncActorId;
        private List<ExternalEmployeeRowCommand> syncRows = List.of();

        @Override
        public HrPageView<HrEmployeeView> employees(String tenantId, int begin, int step,
                                                    EmployeeSearchCriteria criteria) {
            return new HrPageView<>(0, begin, step, List.of());
        }

        @Override
        public Optional<HrEmployeeView> employee(String tenantId, Long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByEmployeeCode(String tenantId, String employeeCode) {
            return false;
        }

        @Override
        public ExternalEmployeeSyncResult syncExternalEmployees(String tenantId, String sourceSystem,
                                                               List<ExternalEmployeeRowCommand> rows,
                                                               String actorId,
                                                               BusinessCodeGenerator codeGenerator) {
            this.syncTenantId = tenantId;
            this.syncSourceSystem = sourceSystem;
            this.syncActorId = actorId;
            this.syncRows = rows == null ? List.of() : List.copyOf(rows);
            return new ExternalEmployeeSyncResult(
                    rows == null ? 0 : rows.size(), 0, 0, rows == null ? 0 : rows.size(),
                    0, List.of(), List.of());
        }

        @Override
        public List<ExternalEmployeeResolvedView> resolveExternalEmployees(String tenantId,
                                                                           String sourceSystem,
                                                                           String sourceTenantKey,
                                                                           List<String> sourceEmployeeIds,
                                                                           List<String> employeeNames) {
            return List.of();
        }
    }
}
