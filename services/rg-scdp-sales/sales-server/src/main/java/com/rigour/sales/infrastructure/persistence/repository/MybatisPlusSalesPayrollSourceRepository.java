package com.rigour.sales.infrastructure.persistence.repository;

import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;
import com.rigour.sales.application.port.out.SalesPayrollSourceStore;
import com.rigour.sales.infrastructure.persistence.mapper.SalesPayrollSourceMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Repository
public class MybatisPlusSalesPayrollSourceRepository implements SalesPayrollSourceStore {
    private final SalesPayrollSourceMapper mapper;

    public MybatisPlusSalesPayrollSourceRepository(SalesPayrollSourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesPayrollEvidenceView evidence(
            String tenant, String employee, LocalDate from, LocalDate to) {
        boolean linked = mapper.linked(tenant, employee) > 0;
        var rows =
                mapper.visits(
                        tenant,
                        employee,
                        from.atStartOfDay().minusHours(8),
                        to.plusDays(1).atStartOfDay().minusHours(8));
        if (rows.size() > 10000) throw new IllegalStateException("月度拜访证据超过上限，请核对来源数据");
        return new SalesPayrollEvidenceView(linked, rows);
    }
}
