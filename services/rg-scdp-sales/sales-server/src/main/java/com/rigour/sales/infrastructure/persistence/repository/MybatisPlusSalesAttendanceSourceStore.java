package com.rigour.sales.infrastructure.persistence.repository;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.sales.application.port.out.SalesAttendanceSourceStore;
import com.rigour.sales.infrastructure.persistence.mapper.SalesAttendanceSourceMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Repository
public class MybatisPlusSalesAttendanceSourceStore implements SalesAttendanceSourceStore {
    private final SalesAttendanceSourceMapper mapper;

    public MybatisPlusSalesAttendanceSourceStore(SalesAttendanceSourceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Evidence> evidence(String tenant, Query query) {
        var codes = query.employeeCodes().stream().distinct().toList();
        if (codes.isEmpty()) return List.of();
        var dates = new HashMap<String, List<LocalDate>>();
        // 考勤只按提交日期去重，不套用拜访绩效的审核、数量和录音门槛。
        mapper.dates(
                        tenant,
                        codes,
                        query.from().atStartOfDay().minusHours(8),
                        query.to().plusDays(1).atStartOfDay().minusHours(8))
                .forEach(
                        r -> {
                            var days =
                                    dates.computeIfAbsent(
                                            r.employeeCode(), key -> new ArrayList<>());
                            if (r.attendanceDate() != null) days.add(r.attendanceDate());
                        });
        return codes.stream()
                .map(
                        code ->
                                new Evidence(
                                        code,
                                        dates.containsKey(code),
                                        List.copyOf(dates.getOrDefault(code, List.of()))))
                .toList();
    }
}
