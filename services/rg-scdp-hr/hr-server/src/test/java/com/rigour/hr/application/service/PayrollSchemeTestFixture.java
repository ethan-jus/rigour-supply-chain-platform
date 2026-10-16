package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollSchemeDefinition;
import com.rigour.hr.api.v1.model.PayrollSchemeVersionView;

import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

public final class PayrollSchemeTestFixture {
    private PayrollSchemeTestFixture() {}

    public static PayrollSchemeDefinition definition() {
        try (var input = new ClassPathResource("payroll/scheme.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(input, PayrollSchemeDefinition.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PayrollSchemeVersionView version(PayrollSchemeDefinition definition) {
        return new PayrollSchemeVersionView(
                "v1",
                "s1",
                1,
                1,
                "PUBLISHED",
                definition,
                "测试",
                "actor",
                "actor",
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
