package com.rigour.shared.core.web;

import com.rigour.shared.core.api.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 通过 Accept 协商响应外壳，兼容仍读取原始 DTO 的服务间客户端。 */
@RestControllerAdvice
public class ApiEnvelopeAdvice implements ResponseBodyAdvice<Object> {
    public static final String MEDIA_TYPE = "application/vnd.rigour.api+json";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (!request.getURI().getPath().startsWith("/api/v1/")
                || !(MediaType.APPLICATION_JSON.includes(contentType) || contentType.getSubtype().endsWith("+json"))
                || body == null || body instanceof Resource || body instanceof byte[] || body instanceof ProblemDetail) {
            return body;
        }
        if (!response.getHeaders().getVary().contains(HttpHeaders.ACCEPT)) {
            response.getHeaders().add(HttpHeaders.VARY, HttpHeaders.ACCEPT);
        }
        boolean requested = request.getHeaders().getAccept().stream()
                .anyMatch(type -> MEDIA_TYPE.equals(type.getType() + "/" + type.getSubtype())
                        && type.getQualityValue() > 0);
        if (!requested || body instanceof ApiResponse<?>) return body;
        if (response instanceof ServletServerHttpResponse servlet) {
            int status = servlet.getServletResponse().getStatus();
            if (status < 200 || status >= 300 || status == 204 || status == 205) return body;
        }
        return ApiResponse.success(body);
    }
}
