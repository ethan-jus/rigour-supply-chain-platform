package com.rigour.tenant.iam.infrastructure.config;

import com.rigour.tenant.iam.infrastructure.security.oidc.OidcServerProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.OidcTokenProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamAccessTokenValidator;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamCurrentSessionTokenValidator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.rigour.tenant.iam.infrastructure.security.session.IamLoginAuthenticationFilter;
import com.rigour.tenant.iam.infrastructure.security.session.IamSessionLogoutHandler;
import com.rigour.tenant.iam.infrastructure.security.session.OidcPromptLoginFilter;
import com.rigour.tenant.iam.infrastructure.security.session.OidcLoginAuthenticationSuccessHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** OIDC协议端点与浏览器会话安全链；总开关关闭时所有非健康检查请求失败关闭。 */
@Configuration(proxyBeanMethods = false)
public class IamAuthorizationServerSecurityConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    AuthorizationServerSettings authorizationServerSettings(OidcServerProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.requireIssuer()).build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService consentService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            @Qualifier("oidcCorsConfigurationSource") CorsConfigurationSource oidcCorsConfigurationSource,
            IamSessionLogoutHandler iamSessionLogoutHandler,
            RequestCache oidcRequestCache
    ) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = authorizationServer.getEndpointsMatcher();
        http.securityMatcher(endpointsMatcher)
                .cors(cors -> cors.configurationSource(oidcCorsConfigurationSource))
                .requestCache(cache -> cache.requestCache(oidcRequestCache))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .with(authorizationServer, server -> server
                        .registeredClientRepository(registeredClientRepository)
                        .authorizationService(authorizationService)
                        .authorizationConsentService(consentService)
                        .authorizationServerSettings(authorizationServerSettings)
                        .tokenGenerator(tokenGenerator)
                        .oidc(oidc -> oidc.logoutEndpoint(logout -> {
                            OidcLogoutAuthenticationSuccessHandler successHandler =
                                    new OidcLogoutAuthenticationSuccessHandler();
                            successHandler.setLogoutHandler(new CompositeLogoutHandler(
                                    iamSessionLogoutHandler,
                                    new SecurityContextLogoutHandler(),
                                    new CookieClearingLogoutHandler("RIGOUR_IAM_SESSION")));
                            logout.logoutResponseHandler(successHandler);
                        })))
                .addFilterBefore(
                        new OidcPromptLoginFilter(oidcRequestCache, iamSessionLogoutHandler),
                        AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    CorsConfigurationSource oidcCorsConfigurationSource(OidcServerProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // 生产只返回明确配置的HTTPS来源；local打开allow-insecure-lan后，属性类会追加私有网段模式。
        // Spring会把匹配到的真实Origin回写到响应头，不会把通配符本身暴露给浏览器。
        configuration.setAllowedOriginPatterns(properties.requireAllowedOriginPatterns());
        configuration.setAllowedMethods(java.util.List.of("GET", "POST"));
        configuration.setAllowedHeaders(java.util.List.of("Accept", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/.well-known/**", configuration);
        source.registerCorsConfiguration("/oauth2/token", configuration);
        source.registerCorsConfiguration("/oauth2/jwks", configuration);
        return source;
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    RequestCache oidcRequestCache() {
        // 授权链和登录链共用同一个会话请求缓存，避免旧的错误地址污染登录成功跳转。
        return new HttpSessionRequestCache();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    SecurityFilterChain portalApiSecurityFilterChain(
            HttpSecurity http,
            JWKSource<SecurityContext> jwkSource,
            OidcServerProperties serverProperties,
            OidcTokenProperties tokenProperties,
            JdbcTemplate jdbcTemplate
    ) throws Exception {
        JwtDecoder rawDecoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        if (!(rawDecoder instanceof NimbusJwtDecoder decoder)) {
            throw new IllegalStateException("Nimbus JWT decoder is required");
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(serverProperties.requireIssuer()),
                new IamAccessTokenValidator(tokenProperties.getAccessTokenAudience()),
                new IamCurrentSessionTokenValidator(jdbcTemplate)));
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/feishu/exchange").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(decoder)));
        return http.build();
    }

    @Bean
    @Order(3)
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    SecurityFilterChain browserSecurityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            IamSessionLogoutHandler logoutHandler,
            OidcServerProperties serverProperties,
            @Qualifier("oidcCorsConfigurationSource") CorsConfigurationSource oidcCorsConfigurationSource,
            RequestCache oidcRequestCache
    ) throws Exception {
        IamLoginAuthenticationFilter loginFilter = new IamLoginAuthenticationFilter(authenticationManager);
        loginFilter.setSecurityContextRepository(securityContextRepository);
        OidcLoginAuthenticationSuccessHandler successHandler =
                new OidcLoginAuthenticationSuccessHandler(oidcRequestCache);
        successHandler.setDefaultTargetUrl(serverProperties.requirePrimaryPortalEntryUri());
        loginFilter.setAuthenticationSuccessHandler(successHandler);
        loginFilter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login?error"));
        http.cors(cors -> cors.configurationSource(oidcCorsConfigurationSource))
                .requestCache(cache -> cache.requestCache(oidcRequestCache))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/brand/**", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/logout").authenticated()
                        .anyRequest().denyAll())
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout
                        .addLogoutHandler(logoutHandler)
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("RIGOUR_IAM_SESSION"))
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(4)
    @ConditionalOnProperty(
            prefix = "rigour.iam.oidc.server",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    SecurityFilterChain disabledAuthorizationServerFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/feishu/exchange").permitAll()
                .anyRequest().denyAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/auth/feishu/exchange"));
        return http.build();
    }
}
