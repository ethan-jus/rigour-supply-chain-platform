package com.rigour.gateway.config;

import com.rigour.gateway.security.AccessTokenClaimsValidator;
import com.rigour.gateway.security.TrustedContextFilter;
import com.rigour.gateway.security.CurrentTokenValidationFilter;
import com.rigour.gateway.security.GatewaySecurityFailureWriter;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpMethod;

/** Gateway资源服务器安全链；只有显式配置IAM信任锚后才接受业务请求。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rigour.gateway.security", name = "enabled", havingValue = "true")
    JwtDecoder gatewayJwtDecoder(GatewaySecurityProperties properties) {
        properties.requireCurrentTokenValidation();
        String issuer = properties.requireIssuer();
        List<String> audience = properties.requireAudience();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.requireJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AccessTokenClaimsValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "rigour.gateway.security", name = "enabled", havingValue = "true")
    SecurityFilterChain resourceServerSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            TrustedContextFilter trustedContextFilter,
            CurrentTokenValidationFilter currentTokenValidationFilter,
            GatewaySecurityFailureWriter failureWriter
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                CurrentTokenValidationFilter.PUBLIC_FEISHU_JSAPI_SIGN_PATH,
                                CurrentTokenValidationFilter.PUBLIC_FEISHU_AUTH_EXCHANGE_PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(failureWriter.tokenInvalidEntryPoint())
                        .accessDeniedHandler(failureWriter.forbiddenHandler()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .addFilterAfter(currentTokenValidationFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(trustedContextFilter, CurrentTokenValidationFilter.class);
        return http.build();
    }

    @Bean
    GatewaySecurityFailureWriter gatewaySecurityFailureWriter(tools.jackson.databind.ObjectMapper objectMapper) {
        return new GatewaySecurityFailureWriter(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.gateway.security", name = "enabled", havingValue = "true")
    TrustedContextFilter trustedContextFilter(TrustedContextSigner contextSigner) {
        return new TrustedContextFilter(contextSigner);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.gateway.security", name = "enabled", havingValue = "true")
    CurrentTokenValidationFilter currentTokenValidationFilter(
            RestClient.Builder builder,
            GatewaySecurityProperties properties,
            GatewaySecurityFailureWriter failureWriter) {
        properties.requireCurrentTokenValidation();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getCurrentTokenConnectTimeout());
        requestFactory.setReadTimeout(properties.getCurrentTokenReadTimeout());
        return new CurrentTokenValidationFilter(
                builder.clone().requestFactory(requestFactory).build(), properties, failureWriter);
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(
            prefix = "rigour.gateway.security", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain disabledGatewaySecurityFilterChain(
            HttpSecurity http,
            GatewaySecurityFailureWriter failureWriter) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                CurrentTokenValidationFilter.PUBLIC_FEISHU_JSAPI_SIGN_PATH,
                        CurrentTokenValidationFilter.PUBLIC_FEISHU_AUTH_EXCHANGE_PATH).permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(failureWriter.sessionCheckUnavailableEntryPoint())
                        .accessDeniedHandler(failureWriter.sessionCheckUnavailableHandler()));
        return http.build();
    }
}
