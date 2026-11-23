package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** HR 员工主档 Mapper。 */
@Mapper
public interface HrEmployeeMapper extends BaseMapper<HrEmployeeEntity> {
    @Insert("""
            INSERT INTO hr_employee_city_history (tenant_id, employee_code, effective_at, city_name)
            VALUES (#{tenant}, #{employee}, #{at}, #{city})
            ON DUPLICATE KEY UPDATE city_name = #{city}
            """)
    void recordCity(@Param("tenant") String tenant, @Param("employee") String employee,
            @Param("at") LocalDateTime at, @Param("city") String city);

}
