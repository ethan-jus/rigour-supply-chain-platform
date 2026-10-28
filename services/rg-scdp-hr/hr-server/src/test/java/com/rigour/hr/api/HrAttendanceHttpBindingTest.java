package com.rigour.hr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.hr.api.v1.model.AttendanceRecordCommand;
import com.rigour.hr.api.v1.model.AttendanceReopenCommand;
import com.rigour.hr.application.service.HrAttendanceService;
import com.rigour.shared.core.web.GlobalExceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class HrAttendanceHttpBindingTest {
    @Test
    void batchRuleBodyBindsThroughTheRealController() throws Exception {
        var service = mock(HrAttendanceService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(new HrAttendanceController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        var commands =
                List.of(
                        new com.rigour.hr.api.v1.model.AttendanceRuleCommand("2026-10", 1L, 22, 0),
                        new com.rigour.hr.api.v1.model.AttendanceRuleCommand("2026-10", 2L, 21, 0));
        mvc.perform(
                        put("/api/v1/hr/attendance/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new tools.jackson.databind.ObjectMapper()
                                                .writeValueAsString(commands)))
                .andExpect(status().isOk());
        verify(service).saveRules(commands);
    }

    @Test
    void confirmBindsMonthEmployeeCodeAndBodyThroughRealController() throws Exception {
        var service = mock(HrAttendanceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HrAttendanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var command = new AttendanceRecordCommand(new BigDecimal("21.5"), new BigDecimal("0.5"),
                "NONE", "核定出勤", 3);
        mvc.perform(post("/api/v1/hr/attendance/2026-09/EMP202610230001/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(command)))
                .andExpect(status().isOk());
        verify(service).confirmRecord("2026-09", "EMP202610230001", command);
        verifyNoMoreInteractions(service);
    }

    @Test
    void reopenBindsMonthEmployeeCodeAndBodyThroughRealController() throws Exception {
        var service = mock(HrAttendanceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HrAttendanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var command = new AttendanceReopenCommand(4, "规则变更重新核定");
        mvc.perform(post("/api/v1/hr/attendance/2026-09/EMP202610230001/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(command)))
                .andExpect(status().isOk());
        verify(service).reopenRecord("2026-09", "EMP202610230001", command);
        verifyNoMoreInteractions(service);
    }

    @Test
    void hrInterfaceBindingsDoNotInferDifferentImplementationParameterNames() throws Exception {
        var errors = new ArrayList<String>();
        for (var controller : List.of(HrAttendanceController.class, HrPayrollController.class,
                HrPayrollSchemeController.class, HrPayrollComponentController.class,
                HrExpenseController.class, HrOrganizationController.class, HrPositionController.class,
                HrEmployeeController.class, CostAttachmentController.class,
                HrCostReferenceController.class, HrCostCityReferenceController.class,
                InternalHrEmployeeProjectionController.class, AuthorizationReferenceController.class,
                com.rigour.hr.api.controller.AnalyticsSourceSnapshotController.class,
                com.rigour.hr.api.controller.HrTargetSettingsController.class)) {
            for (var contract : controller.getInterfaces()) {
                for (var method : contract.getMethods()) {
                    var implementation = controller.getMethod(method.getName(), method.getParameterTypes());
                    var declared = method.getParameters();
                    var actual = implementation.getParameters();
                    for (int i = 0; i < declared.length; i++) {
                        var path = declared[i].getAnnotation(PathVariable.class);
                        var query = declared[i].getAnnotation(RequestParam.class);
                        boolean inferred = path != null && path.value().isEmpty() && path.name().isEmpty()
                                || query != null && query.value().isEmpty() && query.name().isEmpty();
                        if (inferred && !declared[i].getName().equals(actual[i].getName()))
                            errors.add(controller.getSimpleName() + "." + method.getName() + ": "
                                    + declared[i].getName() + " != " + actual[i].getName());
                    }
                }
            }
        }
        assertThat(errors).isEmpty();
    }
}
