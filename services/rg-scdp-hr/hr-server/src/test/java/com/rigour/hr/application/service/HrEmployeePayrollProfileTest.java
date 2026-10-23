package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;

class HrEmployeePayrollProfileTest {
    final HrOrganizationStore store=mock(HrOrganizationStore.class);
    final HrEmployeeStore employees=mock(HrEmployeeStore.class);
    final HrOrganizationService service=new HrOrganizationService(store,employees,mock(HrEmployeeCityScopeService.class));
    @BeforeEach void setup() {
        UUID user=UUID.randomUUID(),tenant=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("hr:employee:create", "hr:employee:update")));
        when(store.saveEmployee(anyString(),isNull(),any(),anyString())).thenReturn(10L);
        when(employees.employee(anyString(),eq(10L))).thenReturn(Optional.of(mock(HrEmployeeView.class)));
    }
    @AfterEach void clear(){ TestAuthorizationContext.clear(); }
    HrEmployeeCommand command(String category,Boolean regularized,LocalDate date,String salary) {
        var profile=new HrEmployeeProfile(null,null,null,null,null,null,null,null,null,null,null,null,null,salary,"3000",null);
        return new HrEmployeeCommand("测试员工",1L,"SALES","ACTIVE",null,null,
                Instant.parse("2026-01-01T00:00:00Z"),null,null,0,profile,null,regularized,date,category,LocalDate.of(2026,1,1));
    }
    @Test void savesExplicitRegularizationAndCategoryAlongsideOriginalProfile() {
        service.saveEmployee(null,command("SALES",true,LocalDate.of(2026,3,1),"5000.50"));
        var saved=ArgumentCaptor.forClass(HrEmployeeCommand.class);
        verify(store).saveEmployee(anyString(),isNull(),saved.capture(),anyString());
        assertThat(saved.getValue().regularized()).isTrue();
        assertThat(saved.getValue().regularizationDate()).isEqualTo(LocalDate.of(2026,3,1));
        assertThat(saved.getValue().profile().regularSalary()).isEqualTo("5000.50");
    }
    @Test void rejectsCompositeSalesSalaryButPreservesHeadquartersArchive() {
        assertThatThrownBy(()->service.saveEmployee(null,command("SALES",false,null,"3000+1000"))).hasMessageContaining("底薪金额");
        verifyNoInteractions(store);
        service.saveEmployee(null,command("HEADQUARTERS",false,null,"3000+1000"));
        verify(store).saveEmployee(anyString(),isNull(),any(),anyString());
    }
    @Test void rejectsRegularizationWithoutActualDateOrBeforeEntry() {
        assertThatThrownBy(()->service.saveEmployee(null,command("SALES",true,null,"5000"))).hasMessageContaining("转正日期");
        assertThatThrownBy(()->service.saveEmployee(null,command("SALES",true,LocalDate.of(2025,12,1),"5000"))).hasMessageContaining("早于入职");
        verifyNoInteractions(store);
    }

    @Test
    void missingPayrollFieldsReturnBadRequestBeforeCreatingManualEmployee() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.rigour.hr.api.HrOrganizationController(service))
                .setControllerAdvice(new com.rigour.shared.core.web.GlobalExceptionHandler())
                .build();
        var json = new tools.jackson.databind.ObjectMapper();
        for (var invalid : List.of(
                command(null, false, null, "5000"),
                command("", false, null, "5000"),
                command("HEADQUARTERS", null, null, "5000"))) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/hr/employees")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalid)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                            .value(invalid.regularized() == null ? "请选择是否转正" : "请选择薪酬类别"));
        }
        verifyNoInteractions(store);
    }

    @Test
    void missingPayrollFieldsCannotClearSavedArchiveAndExplicitUpdateStillSucceeds() throws Exception {
        var original = command("HEADQUARTERS", false, null, "6000");
        var persisted = new java.util.concurrent.atomic.AtomicReference<>(original);
        var saved = mock(HrEmployeeView.class);
        when(saved.employmentStatus()).thenReturn("ACTIVE");
        when(employees.employee(anyString(), eq(10L))).thenReturn(Optional.of(saved));
        when(store.saveEmployee(anyString(), eq(10L), any(), anyString())).thenAnswer(invocation -> {
            persisted.set(invocation.getArgument(2));
            return 10L;
        });
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.rigour.hr.api.HrOrganizationController(service))
                .setControllerAdvice(new com.rigour.shared.core.web.GlobalExceptionHandler())
                .build();
        var json = new tools.jackson.databind.ObjectMapper();
        for (var invalid : List.of(
                updateCommand(original, null, false),
                updateCommand(original, "HEADQUARTERS", null))) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/hr/employees/10")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalid)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                            .value(invalid.regularized() == null ? "请选择是否转正" : "请选择薪酬类别"));
            assertThat(persisted.get()).isSameAs(original);
        }
        verifyNoInteractions(store);
        service.saveEmployee(10L, updateCommand(original, "HEADQUARTERS", false));
        assertThat(persisted.get().payrollCategory()).isEqualTo("HEADQUARTERS");
        assertThat(persisted.get().regularized()).isFalse();
        assertThat(persisted.get().profile().regularSalary()).isEqualTo("6000");
        assertThat(persisted.get().revision()).isEqualTo(1);
    }

    private HrEmployeeCommand updateCommand(HrEmployeeCommand original, String category, Boolean regularized) {
        return new HrEmployeeCommand(original.employeeName(), original.departmentId(), original.positionCode(),
                original.employmentStatus(), original.mobile(), original.email(), original.entryDate(),
                original.leaveDate(), original.remark(), 1, original.profile(), original.jobGrade(),
                regularized, null, category, original.salaryEffectiveFrom());
    }
}
