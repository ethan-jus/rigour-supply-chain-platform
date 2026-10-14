package com.rigour.sales.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.sales.infrastructure.persistence.entity.SalesPayrollSubmissionEntity;

import org.apache.ibatis.annotations.*;

import java.time.*;
import java.util.List;

@Mapper
public interface SalesAttendanceSourceMapper extends BaseMapper<SalesPayrollSubmissionEntity> {
    record AttendanceDate(String employeeCode, LocalDate attendanceDate) {}

    @Select(
            """
<script>
SELECT e.employee_code employeeCode, DATE(CONVERT_TZ(s.submitted_at,'+00:00','+08:00')) attendanceDate
FROM temp_sales_checkin_employee_link e LEFT JOIN temp_sales_checkin_submission s
  ON s.tenant_id=e.tenant_id AND s.salesperson_id=e.salesperson_id
  AND s.status='SUBMITTED' AND s.deletion_state='NONE'
  AND s.submitted_at &gt;= #{from} AND s.submitted_at &lt; #{until}
WHERE e.tenant_id=UUID_TO_BIN(#{tenant}) AND e.employee_code IN
<foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach>
GROUP BY e.employee_code,DATE(CONVERT_TZ(s.submitted_at,'+00:00','+08:00'))
ORDER BY e.employee_code,attendanceDate
</script>
""")
    List<AttendanceDate> dates(
            @Param("tenant") String tenant,
            @Param("codes") List<String> codes,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);
}
