package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

class PayrollRuleCodeTest {
    @Test
    void generatesBusinessCodeOnceAndKeepsItAcrossRevisionsAndRetries() {
        var mapper = mock(HrPayrollComponentMapper.class);
        var locks = mock(HrPayrollPersistenceSupport.class);
        var repository =
                new MybatisPlusHrPayrollComponentRepository(
                        mapper,
                        mock(HrPayrollConfigurationMapper.class),
                        locks,
                        JsonMapper.builder().build(),
                        mock(MybatisPlusHrPayrollComponentLifecycleRepository.class));
        var d =
                new PayrollComponentDefinition(
                        "补贴",
                        "ALLOWANCE",
                        "SALES",
                        List.of(1L),
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(9999, 12, 31),
                        null,
                        null,
                        null,
                        null,
                        new PayrollAllowanceStandard(List.of(), "NONE"),
                        List.of());
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectCount(any())).thenReturn(0L);
        var first =
                repository.save(
                        "tenant",
                        new PayrollComponentSaveCommand("request-1", null, d, "创建"),
                        "actor");
        assertThat(first.ruleCode()).matches("PCR[0-9]{14}");
        var captured = ArgumentCaptor.forClass(HrPayrollComponentEntity.class);
        verify(mapper).insert(captured.capture());
        var stored = captured.getValue();
        when(mapper.selectList(any())).thenReturn(List.of(stored));
        var second =
                repository.save(
                        "tenant",
                        new PayrollComponentSaveCommand("request-2", first.id(), d, "修订"),
                        "actor");
        assertThat(second.ruleCode()).isEqualTo(first.ruleCode());
        assertThat(second.ruleId()).isEqualTo(first.ruleId());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.id()).isNotEqualTo(first.id());
        when(mapper.selectOne(any())).thenReturn(stored);
        var retry =
                repository.save(
                        "tenant",
                        new PayrollComponentSaveCommand("request-1", null, d, "创建"),
                        "actor");
        assertThat(retry).isEqualTo(first);
        verify(mapper, times(2)).insert(any(HrPayrollComponentEntity.class));
    }
}
