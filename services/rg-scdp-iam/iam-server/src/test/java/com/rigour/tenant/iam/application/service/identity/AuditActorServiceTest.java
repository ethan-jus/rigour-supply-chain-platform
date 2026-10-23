package com.rigour.tenant.iam.application.service.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.tenant.iam.application.port.out.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class AuditActorServiceTest {
    @Test
    void usesOperatorAccountNameInsteadOfBoundEmployeeName() {
        var reader = mock(AuditActorReader.class);
        var hr = mock(AppEmployeeClient.class);
        var service = new AuditActorService(reader, hr);
        UUID tenant = UUID.randomUUID(), creator = UUID.randomUUID(), viewer = UUID.randomUUID();
        var user =
                new CurrentUser(
                        viewer, tenant, "测试企业", "TENANT", "viewer", "当前查看人", Set.of(), Set.of());
        var refs = List.of(creator.toString(), "EMP1");
        when(reader.actors(tenant, refs))
                .thenReturn(
                        List.of(
                                new AuditActorReader.ActorName(
                                        creator.toString(), "EMP1", "录入账号名称")));
        var employee =
                new AppEmployeeClient.Employee(
                        1,
                        "EMP1",
                        "绑定员工姓名",
                        "ACTIVE",
                        1L,
                        "部门",
                        "P1",
                        "岗位",
                        List.of(1L),
                        1,
                        1,
                        1,
                        true,
                        null);
        when(hr.employees(tenant, List.of("EMP1"))).thenReturn(Map.of("EMP1", employee));
        assertThat(service.resolve(user, refs))
                .containsEntry(creator.toString(), "录入账号名称")
                .containsEntry("EMP1", "绑定员工姓名")
                .doesNotContainValue("当前查看人");
    }

    @Test
    void resolvesOnlyCurrentTenantAndReturnsNamesWithoutIdentityDetails() {
        var reader = mock(AuditActorReader.class);
        var hr = mock(AppEmployeeClient.class);
        var service = new AuditActorService(reader, hr);
        UUID tenant = UUID.randomUUID(), id = UUID.randomUUID();
        var user =
                new CurrentUser(id, tenant, "测试企业", "TENANT", "admin", "管理员", Set.of(), Set.of());
        var refs = List.of(id.toString(), "other");
        when(reader.actors(tenant, refs))
                .thenReturn(List.of(new AuditActorReader.ActorName(id.toString(), null, "管理员")));
        assertThat(service.resolve(user, refs))
                .containsExactlyEntriesOf(Map.of(id.toString(), "管理员"));
        verify(reader).actors(tenant, refs);
        verifyNoInteractions(hr);
        assertThatThrownBy(
                        () ->
                                service.resolve(
                                        new CurrentUser(
                                                id,
                                                null,
                                                null,
                                                "PLATFORM",
                                                "admin",
                                                "管理员",
                                                Set.of(),
                                                Set.of()),
                                        refs))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.resolve(user, Collections.nCopies(101, "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
