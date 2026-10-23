package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrExpenseEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HrExpenseMapper extends BaseMapper<HrExpenseEntity> {
    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO hr_expense_guard(tenant_id,version) VALUES(#{tenant},0)")
    void ensureGuard(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "SELECT version FROM hr_expense_guard WHERE tenant_id=#{tenant} FOR UPDATE")
    long lock(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @Update("UPDATE hr_expense_guard SET version=version+1 WHERE tenant_id=#{tenant}")
    void advanceAnalyticsVersion(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "SELECT COALESCE((SELECT version FROM hr_expense_guard WHERE tenant_id=#{tenant}),0)")
    String analyticsVersion(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM hr_expense_attachment WHERE tenant_id=#{tenant} AND"
                    + " entry_id=#{id}")
    long attachmentCount(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("id") String id);

    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO hr_expense_cost_link(tenant_id,expense_id,source_service,source_id)"
                    + " VALUES(#{tenant},#{id},#{source},#{original})")
    void claimCostLink(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("id") String id,
            @org.apache.ibatis.annotations.Param("source") String source,
            @org.apache.ibatis.annotations.Param("original") String original);

    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM hr_expense_cost_link WHERE tenant_id=#{tenant} AND expense_id=#{id}")
    void releaseCostLink(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("id") String id);
}
