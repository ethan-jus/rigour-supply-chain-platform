package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PayrollPerformanceReportFactsTest {
    final JsonMapper json = JsonMapper.builder().build();
    PayrollDetailView row() {
        var fixture = new HrPayrollPerformanceExportServiceTest();
        ObjectNode node = json.valueToTree(fixture.row());
        var snapshot = new PayrollSchemeSnapshotView(PayrollSchemeTestFixture.version(PayrollSchemeTestFixture.definition()),
                "BRONZE", BigDecimal.ZERO, new BigDecimal("2000"), List.of(), null, null, 0, 0, List.of());
        ((ObjectNode)node.get("calculation")).set("scheme", json.valueToTree(snapshot));
        return json.treeToValue(node, PayrollDetailView.class);
    }
    @Test void newJoinerIsExemptRatherThanMissingAndOtherTargetsRemainIndependent() {
        var r = row();
        var report = HrPayrollPerformanceExportService.summarize(r, List.of(), Map.of(), Map.of(), null, null);
        var employee = new PayrollEmployeeView("E1", "测试", 1L, "北京", "北京", LocalDate.of(2026, 10, 29), null, "SALES");
        var result = PayrollPerformanceReportFacts.enrich(r, report, employee, null, List.of(), List.of());
        assertThat(result.collectionTarget()).isZero();
        assertThat(result.collectionTargetBasis()).contains("首个自然月", "免考核");
        assertThat(result.salesTarget()).isNull();
        assertThat(result.issues()).doesNotContain("销售指标未配置", "回款指标未配置");
        assertThat(result.summary().actualDays()).isNull();
    }
    @Test void calendarMonthStagesComeFromSavedRuleWithoutUsingDaysOrHardcodedAmounts() {
        var r = row();
        var report = HrPayrollPerformanceExportService.summarize(r, List.of(), Map.of(), Map.of(), null, null);
        for (var pair : List.of(new String[]{"2026-09-30", "5000"}, new String[]{"2026-08-31", "7000"}, new String[]{"2025-12-31", "7000"})) {
            var e = new PayrollEmployeeView("E1", "测试", 1L, "北京", "北京", LocalDate.parse(pair[0]), null, "SALES");
            var result = PayrollPerformanceReportFacts.enrich(r, report, e, null, List.of(), List.of());
            assertThat(result.collectionTarget()).isEqualByComparingTo(pair[1]);
            assertThat(result.issues()).doesNotContain("回款指标未配置");
        }
    }
    @Test void targetsCannotBecomeActualsAndStoreMetricsAreNotAdded() {
        var r = row();
        var report = HrPayrollPerformanceExportService.summarize(r, List.of(), Map.of(), Map.of(), new BigDecimal("5000"), null);
        var employee = new PayrollEmployeeView("E1", "测试", 1L, "北京", "北京", LocalDate.of(2026, 9, 29), null, "SALES");
        var goal = new TargetSettingsModels.Target("2026-10", "SALES_OWNER", "E1", "测试", "YANGZHANGGUI_SALES_AMOUNT", new BigDecimal("150"), 1);
        var result = PayrollPerformanceReportFacts.enrich(r, report, employee, null, List.of(goal), List.of());
        assertThat(result.collectionTarget()).isEqualByComparingTo("5000");
        assertThat(result.metrics()).noneMatch(m -> Set.of("CONTACTED_STORE_COUNT", "ORDERED_STORE_COUNT").contains(m.code()));
        assertThat(result.metrics()).filteredOn(m -> m.code().equals("YANGZHANGGUI_SALES_AMOUNT")).singleElement().satisfies(m -> {
            assertThat(m.actual()).isNull(); assertThat(m.target()).isEqualByComparingTo("150");
        });
    }
    @Test void confirmedManualTotalsAreRealAchievementAndCanExceedTheTarget() {
        var r = row();
        var report = HrPayrollPerformanceExportService.summarize(r, List.of(), Map.of(), Map.of(), new BigDecimal("5000"), null);
        var employee = new PayrollEmployeeView("E1", "测试", 1L, "北京", "北京", LocalDate.of(2026, 9, 29), null, "SALES");
        var adjustment = new AttendanceAssessmentOverride(0, 2, "业务真实核定", 160, 70, 154, 66);
        var node = json.createObjectNode();
        node.put("employeeCode", "E1"); node.put("attendanceStatus", "CONFIRMED"); node.put("expectedDays", 22); node.put("actualDays", 22); node.put("revision", 0);
        node.set("assessmentOverride", json.valueToTree(adjustment));
        var result = PayrollPerformanceReportFacts.enrich(r, report, employee, json.treeToValue(node, AttendanceRow.class), List.of(), List.of());
        assertThat(result.summary().expectedCheckins()).isEqualTo(154);
        assertThat(result.summary().actualCheckins()).isEqualTo(160);
        assertThat(result.summary().expectedVisits()).isEqualTo(66);
        assertThat(result.summary().actualVisits()).isEqualTo(70);
        assertThat(result.issues()).contains("打卡/拜访采用人工核定实绩");
    }
}
