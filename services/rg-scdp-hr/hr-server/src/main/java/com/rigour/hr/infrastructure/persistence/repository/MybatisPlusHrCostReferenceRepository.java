package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.model.HrCostEmployeeView;
import com.rigour.hr.application.port.out.HrCostReferenceStore;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrPayrollReferenceMapper;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisPlusHrCostReferenceRepository implements HrCostReferenceStore {
    private final HrPayrollReferenceMapper mapper;
    private final HrDataScope scope;

    public MybatisPlusHrCostReferenceRepository(
            HrPayrollReferenceMapper mapper, HrDataScope scope) {
        this.mapper = mapper;
        this.scope = scope;
    }

    @Override
    public List<HrCostEmployeeView> employees(String tenant, String action) {
        var q = new QueryWrapper<HrEmployeeEntity>().eq("e.tenant_id", tenant).eq("e.deleted", 0);
        var p = scope.costEmployeePredicate(action, "e.");
        HrPayrollPersistenceSupport.bind(q, p.sql(), p.args());
        var rows = mapper.employees(q.orderByAsc("e.employee_code").last("LIMIT 20001"));
        if (rows.size() > 20000) throw new IllegalStateException("员工引用超出上限");
        return rows.stream()
                .map(
                        e ->
                                new HrCostEmployeeView(
                                        e.employeeCode(),
                                        e.employeeName(),
                                        e.departmentId(),
                                        e.departmentName()))
                .toList();
    }
}
