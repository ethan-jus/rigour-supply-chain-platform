package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollSchemeEntity;

@org.apache.ibatis.annotations.Mapper
public interface HrPayrollSchemeMapper extends BaseMapper<HrPayrollSchemeEntity> {
    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO"
                + " hr_payroll_scheme_event(id,tenant_id,subject_id,event_type,data_json,reason,actor,created_time)"
                + " VALUES(#{id},#{tenant},#{subject},#{type},#{data},#{reason},#{actor},UTC_TIMESTAMP(6))")
    void event(
            @org.apache.ibatis.annotations.Param("id") String id,
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("subject") String subject,
            @org.apache.ibatis.annotations.Param("type") String type,
            @org.apache.ibatis.annotations.Param("data") String data,
            @org.apache.ibatis.annotations.Param("reason") String reason,
            @org.apache.ibatis.annotations.Param("actor") String actor);
}
