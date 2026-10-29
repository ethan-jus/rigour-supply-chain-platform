package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.application.port.out.HrEmployeeStore.EmployeeSearchCriteria;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeSourceBindingMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrPositionMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class HrEmployeeFilterQueryTest {
    @Test
    void nameCodeMobileAndDhbAccountUseSeparateConditionsAndBoundTenant() {
        var repository = new MybatisPlusHrEmployeeRepository(mock(HrEmployeeMapper.class),
                mock(HrPositionMapper.class), mock(HrEmployeeSourceBindingMapper.class),
                mock(PlatformTransactionManager.class), Clock.systemUTC(), mock(HrDataScope.class));
        var criteria = JsonMapper.builder().build().convertValue(Map.of(
                "employeeName", "姓名甲", "employeeCode", "E1", "mobile", "138", "dhbAccount", "login100%"), EmployeeSearchCriteria.class);
        QueryWrapper<?> query = ReflectionTestUtils.invokeMethod(repository, "employeeQuery", "TENANT", criteria);
        assertThat(query).isNotNull();
        String sql = query.getSqlSegment();
        assertThat(sql).contains("employee_name LIKE", "employee_code =", "mobile LIKE", "source_account_name", "source_system='DINGHUOBAO'", "deleted=0");
        assertThat(sql).doesNotContain(" OR ", "login100%", "姓名甲");
        assertThat(query.getParamNameValuePairs().values()).contains("TENANT", "E1", "%姓名甲%", "%138%", "login100%");
    }
}
