package com.rigour.gateway.security;

import com.rigour.shared.context.AuthenticationFailureCodes;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.core.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/** Gateway认证边界的稳定错误响应；浏览器只在明确的Token失效时清理登录态。 */
public final class GatewaySecurityFailureWriter {

    private final ObjectMapper objectMapper;

    public GatewaySecurityFailureWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint tokenInvalidEntryPoint() {
        return (request, response, exception) -> tokenInvalid(response);
    }

    public AccessDeniedHandler forbiddenHandler() {
        return (request, response, exception) -> forbidden(response);
    }

    public AuthenticationEntryPoint sessionCheckUnavailableEntryPoint() {
        return (request, response, exception) -> sessionCheckUnavailable(response);
    }

    public AccessDeniedHandler sessionCheckUnavailableHandler() {
        return (request, response, exception) -> sessionCheckUnavailable(response);
    }

    public void tokenInvalid(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, AuthenticationFailureCodes.IAM_TOKEN_INVALID,
                "登录凭证无效或已过期");
    }

    public void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, AuthenticationFailureCodes.IAM_FORBIDDEN,
                "没有访问该资源的权限");
    }

    public void sessionCheckUnavailable(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.SERVICE_UNAVAILABLE,
                AuthenticationFailureCodes.IAM_SESSION_CHECK_UNAVAILABLE, "IAM会话校验暂不可用");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(RequestHeaders.AUTH_FAILURE, code);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, List.of()));
    }
}
