package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityLookup;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityMatch;
import com.rigour.hr.api.v1.HrEmployeeProjectionApi.IdentityMatchMethod;
import com.rigour.hr.api.v1.model.HrDepartmentView;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSourceBindingEntity;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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

    @Test
    void huolalaUsesUniquePhoneOrUniqueNameAndRejectsConflicts() {
        var organization = mock(JdbcHrOrganizationStore.class);
        ReflectionTestUtils.setField(repository, "organization", organization);
        when(organization.departments("T")).thenReturn(List.of(new HrDepartmentView(
                8L, null, "D8", "武汉销售部", 0, "ACTIVE", 1,
                null, null, null, null, null, null, null, null, null, null)));
        var eligible = employee("ACTIVE");
        eligible.id = 1L;
        eligible.mobile = "+86 138-0000-0001";
        eligible.departmentId = 8L;
        eligible.departmentNameSnapshot = "旧部门名称";
        var shared = employee("ACTIVE");
        shared.id = 2L;
        shared.mobile = "13800000002";
        var retired = employee("LEFT");
        retired.id = 3L;
        retired.mobile = "13800000002";
        var nameOnly = employee("ACTIVE");
        nameOnly.id = 4L;
        nameOnly.employeeCode = "E4";
        nameOnly.employeeName = "姓名唯一";
        nameOnly.mobile = "13800000003";
        nameOnly.departmentId = 8L;
        when(employees.selectList(any(QueryWrapper.class))).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id =", "deleted =", "REGEXP_REPLACE", "TRIM(employee_name)");
            assertThat(query.getParamNameValuePairs().values()).contains("T", 0, "13800000001");
            return List.of(eligible, shared, retired, nameOnly);
        });
        assertThat(repository.matchHuolalaEmployees("T", List.of(
                new IdentityLookup("11", "13800000001", "同名员工", true, true),
                new IdentityLookup("12", "13800000002", "同名员工", true, true),
                new IdentityLookup("13", "13800000003", "同名员工", true, true),
                new IdentityLookup("14", "13800000004", "姓名唯一", true, true),
                new IdentityLookup("15", "", "姓名唯一", false, true),
                new IdentityLookup("16", "", "姓名唯一", false, false),
                new IdentityLookup("17", "13800000005", "其他员工", true, true),
                new IdentityLookup("18", "13800000001", "平台昵称", true, true))))
                .containsExactly(
                        new IdentityMatch("11", 1L, "E1", "同名员工", "武汉销售部", IdentityMatchMethod.MOBILE),
                        new IdentityMatch("14", 4L, "E4", "姓名唯一", "武汉销售部", IdentityMatchMethod.NAME),
                        new IdentityMatch("15", 4L, "E4", "姓名唯一", "武汉销售部", IdentityMatchMethod.NAME),
                        new IdentityMatch("18", 1L, "E1", "同名员工", "武汉销售部", IdentityMatchMethod.MOBILE));
    }
}
