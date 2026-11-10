package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSourceBindingEntity;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class HrExternalEmployeeMobileResolutionTest {
    private final HrEmployeeMapper employees = mock(HrEmployeeMapper.class);
    private final HrEmployeeSourceBindingMapper bindings = mock(HrEmployeeSourceBindingMapper.class);
    private final MybatisPlusHrEmployeeRepository repository = new MybatisPlusHrEmployeeRepository(
            employees, mock(HrPositionMapper.class), bindings,
            mock(PlatformTransactionManager.class), Clock.systemUTC(), mock(HrDataScope.class));

    private HrEmployeeSourceBindingEntity binding(String code, boolean review) {
        var b = new HrEmployeeSourceBindingEntity();
        b.employeeCode = code; b.sourceEmployeeId = "293763";
        b.sourceEmployeeName = "同名员工"; b.reviewRequired = review;
        return b;
    }
    private HrEmployeeEntity employee(String status) {
        var e = new HrEmployeeEntity(); e.employeeCode = "E1";
        e.employeeName = "同名员工"; e.employmentStatus = status;
        return e;
    }
    private void mobileMatches(List<HrEmployeeSourceBindingEntity> rows) {
        when(bindings.selectList(any(QueryWrapper.class))).thenAnswer(invocation -> {
            QueryWrapper<?> q = invocation.getArgument(0);
            String sql = q.getSqlSegment();
            assertThat(sql).contains("tenant_id =", "source_system =", "source_tenant_key =", "deleted =");
            assertThat(q.getParamNameValuePairs().values()).contains("T", "DINGHUOBAO", "connector");
            return sql.contains("source_mobile IN") ? rows : List.of();
        });
        when(employees.selectOne(any(QueryWrapper.class))).thenReturn(employee("ACTIVE"));
    }
    private List<com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView> resolve() {
        return repository.resolveExternalEmployees("T", "DINGHUOBAO", "connector",
                List.of("13800000001"), List.of("同名员工"));
    }
    @Test void mobileResolvesBoundEmployeeDespiteDuplicateNames() {
        mobileMatches(List.of(binding("E1", false)));
        assertThat(resolve()).singleElement().satisfies(e -> assertThat(e.employeeCode()).isEqualTo("E1"));
        verify(employees, never()).selectList(any(QueryWrapper.class));
    }
    @Test void historicalLeftEmployeeStillResolvesByEvidence() {
        mobileMatches(List.of(binding("E1", false)));
        when(employees.selectOne(any(QueryWrapper.class))).thenReturn(employee("LEFT"));
        assertThat(resolve()).singleElement().satisfies(e -> assertThat(e.employeeCode()).isEqualTo("E1"));
    }
    @Test void sharedPhoneCannotFallBackToName() {
        mobileMatches(List.of(binding("E1", false), binding("E2", false)));
        assertThat(resolve()).isEmpty(); verifyNoInteractions(employees);
    }
    @Test void pendingReviewCannotResolveOrFallBackToName() {
        mobileMatches(List.of(binding("E1", true)));
        assertThat(resolve()).isEmpty(); verifyNoInteractions(employees);
    }
    @Test void unmatchedPhoneCannotBorrowSameNameEmployee() {
        mobileMatches(List.of()); assertThat(resolve()).isEmpty(); verifyNoInteractions(employees);
    }
    @Test void nameConflictAndDeletedEmployeeDoNotResolve() {
        mobileMatches(List.of(binding("E1", false)));
        assertThat(repository.resolveExternalEmployees("T", "DINGHUOBAO", "connector",
                List.of("13800000001"), List.of("其他人"))).isEmpty();
        when(employees.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertThat(resolve()).isEmpty();
    }
    @Test void sourceIdMatchTakesPrecedenceOverMobileAlias() {
        when(bindings.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding("E1", false)));
        when(employees.selectList(any(QueryWrapper.class))).thenReturn(List.of(employee("ACTIVE")));
        assertThat(repository.resolveExternalEmployees("T", "DINGHUOBAO", "connector",
                List.of("293763", "13800000001"), List.of())).singleElement()
                .satisfies(e -> assertThat(e.employeeCode()).isEqualTo("E1"));
        verify(bindings, times(1)).selectList(any(QueryWrapper.class));
    }
    @Test void sourceIdPendingReviewCannotAssignBusinessOwnershipByName() {
        when(bindings.selectList(any(QueryWrapper.class))).thenReturn(List.of(binding("E1", true)));
        assertThat(repository.resolveExternalEmployees("T", "DINGHUOBAO", "connector",
                List.of("293763"), List.of("同名员工"))).isEmpty();
        verifyNoInteractions(employees);
    }
}
