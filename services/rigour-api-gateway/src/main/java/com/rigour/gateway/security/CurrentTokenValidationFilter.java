package com.rigour.gateway.security;

import com.rigour.gateway.config.GatewaySecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 向IAM在线确认会话、安全版本和租户策略版本；开启时失败关闭。 */
public final class CurrentTokenValidationFilter extends OncePerRequestFilter {
    private final RestClient restClient;
    private final GatewaySecurityProperties properties;

    public CurrentTokenValidationFilter(RestClient restClient, GatewaySecurityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isCurrentTokenValidationEnabled()
                || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        try {
            CurrentTokenSnapshot snapshot = restClient.get().uri(properties.requireIamCurrentTokenUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue())
                    .retrieve().body(CurrentTokenSnapshot.class);
            if (snapshot == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "IAM returned an empty token snapshot");
                return;
            }
            request.setAttribute(CurrentTokenValidationFilter.class.getName() + ".roles", snapshot.roles());
            request.setAttribute(CurrentTokenValidationFilter.class.getName() + ".permissions", snapshot.permissions());
            filterChain.doFilter(request, response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpServletResponse.SC_UNAUTHORIZED
                    || exception.getStatusCode().value() == HttpServletResponse.SC_FORBIDDEN) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "IAM rejected the current access token");
                return;
            }
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IAM current-token validation is temporarily unavailable");
        } catch (RestClientException exception) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "IAM current-token validation is temporarily unavailable");
        }
    }

    public static String rolesAttribute() { return CurrentTokenValidationFilter.class.getName() + ".roles"; }
    public static String permissionsAttribute() { return CurrentTokenValidationFilter.class.getName() + ".permissions"; }

    public record CurrentTokenSnapshot(Set<String> roles, Set<String> permissions) {
        public CurrentTokenSnapshot {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
}
