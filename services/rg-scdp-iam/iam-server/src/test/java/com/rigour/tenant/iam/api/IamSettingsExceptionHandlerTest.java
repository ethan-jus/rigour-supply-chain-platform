package com.rigour.tenant.iam.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.shared.core.web.GlobalExceptionHandler;
import com.rigour.tenant.iam.api.controller.management.IamAppRoleController;
import com.rigour.tenant.iam.application.service.settings.AppRoleService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IamSettingsExceptionHandlerTest {
    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void returnsValidationAndConflictReasonsWithoutLeakingSql() throws Exception {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("test")
                .claim("principalScope", "TENANT").claim("principalId", UUID.randomUUID().toString())
                .claim("tenantId", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
        var service = mock(AppRoleService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new IamAppRoleController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.save(any(), isNull(), any()))
                .thenThrow(new com.rigour.shared.core.exception.RequestValidationException("角色编码必须以字母开头"))
                .thenThrow(new com.rigour.shared.core.exception.StateConflictException("角色已修改，请刷新"))
                .thenThrow(new DuplicateKeyException("SQL secret database details"));
        mvc.perform(post("/api/v1/management/supply/roles").contentType("application/json").content("{\"version\":0,\"dataScope\":{\"mode\":\"ALL\",\"departmentIds\":[]}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("角色编码必须以字母开头"));
        mvc.perform(post("/api/v1/management/supply/roles").contentType("application/json").content("{\"version\":0,\"dataScope\":{\"mode\":\"ALL\",\"departmentIds\":[]}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("角色已修改，请刷新"));
        mvc.perform(post("/api/v1/management/supply/roles").contentType("application/json").content("{\"version\":0,\"dataScope\":{\"mode\":\"ALL\",\"departmentIds\":[]}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("编码或关联关系已存在，请检查后重试"));
    }
}
