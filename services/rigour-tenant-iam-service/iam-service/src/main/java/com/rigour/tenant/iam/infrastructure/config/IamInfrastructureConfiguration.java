package com.rigour.tenant.iam.infrastructure.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.port.out.PasswordIdentityStore;
import com.rigour.tenant.iam.application.service.auth.AuthService;
import com.rigour.tenant.iam.application.service.auth.PasswordAuthenticationPolicy;
import com.rigour.tenant.iam.application.port.out.PortalAccessReader;
import com.rigour.tenant.iam.application.port.out.IamManagementStore;
import com.rigour.tenant.iam.application.service.management.IamManagementService;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import com.rigour.tenant.iam.infrastructure.bootstrap.PlatformAdminBootstrapCommand;
import com.rigour.tenant.iam.infrastructure.bootstrap.PlatformAdminBootstrapProperties;
import com.rigour.tenant.iam.infrastructure.bootstrap.PortalClientBootstrapCommand;
import com.rigour.tenant.iam.infrastructure.bootstrap.PortalClientBootstrapProperties;
import com.rigour.tenant.iam.infrastructure.bootstrap.TenantAdminBootstrapCommand;
import com.rigour.tenant.iam.infrastructure.bootstrap.TenantAdminBootstrapProperties;
import com.rigour.tenant.iam.infrastructure.bootstrap.LocalSigningKeyBootstrapCommand;
import com.rigour.tenant.iam.infrastructure.bootstrap.LocalSigningKeyBootstrapProperties;
import com.rigour.tenant.iam.infrastructure.persistence.UuidV7IdentifierGenerator;
import com.rigour.tenant.iam.infrastructure.persistence.auth.JdbcPasswordIdentityStore;
import com.rigour.tenant.iam.infrastructure.persistence.auth.JdbcPortalAccessReader;
import com.rigour.tenant.iam.infrastructure.persistence.management.JdbcIamManagementStore;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcOAuth2AuthorizationConsentStore;
import com.rigour.tenant.iam.infrastructure.security.oidc.AesGcmAuthorizationAttributesCipher;
import com.rigour.tenant.iam.infrastructure.security.oidc.AuthorizationAttributesCipher;
import com.rigour.tenant.iam.infrastructure.security.oidc.AuthorizationSessionResolver;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamSessionAuthorizationResolver;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamJwtCustomizer;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamTokenClaimsResolver;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcOAuth2AuthorizationStore;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcRegisteredClientRepository;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcRsaJwkSource;
import com.rigour.tenant.iam.infrastructure.security.oidc.OidcAuthorizationProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.OidcSigningProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.OidcServerProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.OidcTokenProperties;
import com.rigour.tenant.iam.infrastructure.security.oidc.PrivateKeyReferenceResolver;
import com.rigour.tenant.iam.infrastructure.security.oidc.SignedIdTokenDecoder;
import com.rigour.tenant.iam.infrastructure.security.password.PasswordHasherAdapter;
import com.rigour.tenant.iam.infrastructure.security.session.IamPasswordAuthenticationProvider;
import com.rigour.tenant.iam.infrastructure.security.session.PasswordAuthenticationProperties;
import com.rigour.tenant.iam.infrastructure.security.session.IamSessionLogoutHandler;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** IAM基础设施装配入口；Mapper只允许扫描本服务自己的持久化包。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.tenant.iam.infrastructure.persistence.mapper")
@EnableConfigurationProperties({
        OidcAuthorizationProperties.class,
        OidcSigningProperties.class,
        OidcServerProperties.class,
        OidcTokenProperties.class,
        PasswordAuthenticationProperties.class,
        PlatformAdminBootstrapProperties.class,
        PortalClientBootstrapProperties.class,
        TenantAdminBootstrapProperties.class,
        LocalSigningKeyBootstrapProperties.class
})
public final class IamInfrastructureConfiguration {

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasherAdapter();
    }

    @Bean
    PasswordIdentityStore passwordIdentityStore(JdbcTemplate jdbcTemplate) {
        return new JdbcPasswordIdentityStore(jdbcTemplate);
    }

    @Bean
    PortalAccessReader portalAccessReader(JdbcTemplate jdbcTemplate) {
        return new JdbcPortalAccessReader(jdbcTemplate);
    }

    @Bean
    PortalAccessService portalAccessService(PortalAccessReader reader) {
        return new PortalAccessService(reader);
    }

    @Bean
    IamManagementStore iamManagementStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            IdentifierGenerator identifierGenerator,
            PasswordHasher passwordHasher,
            RegisteredClientRepository registeredClientRepository,
            OidcServerProperties oidcServerProperties
    ) {
        return new JdbcIamManagementStore(
                jdbcTemplate, transactionManager, identifierGenerator, passwordHasher, registeredClientRepository,
                oidcServerProperties.isAllowInsecureLoopback());
    }

    @Bean
    IamManagementService iamManagementService(IamManagementStore store) {
        return new IamManagementService(store);
    }

    @Bean
    IdentifierGenerator identifierGenerator() {
        return new UuidV7IdentifierGenerator();
    }

    @Bean
    AuthService authService(
            PasswordIdentityStore passwordIdentityStore,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            PasswordAuthenticationProperties properties
    ) {
        PasswordAuthenticationPolicy policy = new PasswordAuthenticationPolicy(
                properties.getMaximumFailedAttempts(),
                properties.getLockDuration(),
                properties.getSessionTimeToLive());
        return new AuthService(
                passwordIdentityStore, passwordHasher, identifierGenerator, Clock.systemUTC(), policy);
    }

    @Bean
    AuthenticationProvider iamPasswordAuthenticationProvider(
            AuthService authService, PlatformTransactionManager transactionManager) {
        return new IamPasswordAuthenticationProvider(authService, new TransactionTemplate(transactionManager));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    IamSessionLogoutHandler iamSessionLogoutHandler(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new IamSessionLogoutHandler(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rigour.iam.bootstrap.platform-admin",
            name = "enabled",
            havingValue = "true"
    )
    PlatformAdminBootstrapCommand platformAdminBootstrapCommand(
            JdbcTemplate jdbcTemplate,
            PasswordHasher passwordHasher,
            IdentifierGenerator identifierGenerator,
            PlatformTransactionManager transactionManager,
            PlatformAdminBootstrapProperties properties
    ) {
        return new PlatformAdminBootstrapCommand(
                jdbcTemplate, passwordHasher, identifierGenerator,
                new TransactionTemplate(transactionManager), properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rigour.iam.bootstrap.portal-client", name = "enabled", havingValue = "true")
    PortalClientBootstrapCommand portalClientBootstrapCommand(
            RegisteredClientRepository repository,
            IdentifierGenerator identifierGenerator,
            PortalClientBootstrapProperties properties
    ) {
        return new PortalClientBootstrapCommand(repository, identifierGenerator, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.bootstrap.tenant-admin", name = "enabled", havingValue = "true")
    TenantAdminBootstrapCommand tenantAdminBootstrapCommand(
            JdbcTemplate jdbcTemplate, PasswordHasher passwordHasher, IdentifierGenerator identifierGenerator,
            PlatformTransactionManager transactionManager, TenantAdminBootstrapProperties properties) {
        return new TenantAdminBootstrapCommand(jdbcTemplate, passwordHasher, identifierGenerator,
                new TransactionTemplate(transactionManager), properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.bootstrap.local-signing-key", name = "enabled", havingValue = "true")
    LocalSigningKeyBootstrapCommand localSigningKeyBootstrapCommand(
            JdbcTemplate jdbcTemplate, IdentifierGenerator identifierGenerator,
            LocalSigningKeyBootstrapProperties properties) {
        return new LocalSigningKeyBootstrapCommand(jdbcTemplate, identifierGenerator, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    PrivateKeyReferenceResolver privateKeyReferenceResolver() {
        return new PrivateKeyReferenceResolver();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    JWKSource<SecurityContext> jwkSource(
            JdbcTemplate jdbcTemplate,
            PrivateKeyReferenceResolver privateKeyReferenceResolver,
            OidcSigningProperties properties,
            org.springframework.beans.factory.ObjectProvider<LocalSigningKeyBootstrapCommand> localBootstrap
    ) throws Exception {
        LocalSigningKeyBootstrapCommand command = localBootstrap.getIfAvailable();
        if (command != null) command.ensure();
        return new JdbcRsaJwkSource(
                jdbcTemplate, privateKeyReferenceResolver, properties.getMinimumRsaBits());
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.server", name = "enabled", havingValue = "true")
    SignedIdTokenDecoder signedIdTokenDecoder(
            JWKSource<SecurityContext> jwkSource, OidcServerProperties properties) {
        JwtDecoder rawDecoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        if (!(rawDecoder instanceof NimbusJwtDecoder decoder)) {
            throw new IllegalStateException("Nimbus JWT decoder is required");
        }
        decoder.setJwtValidator(new JwtClaimValidator<String>("iss", properties.requireIssuer()::equals));
        return decoder::decode;
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    IamTokenClaimsResolver iamTokenClaimsResolver(JdbcTemplate jdbcTemplate) {
        return new IamTokenClaimsResolver(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "rigour.iam.oidc.signing", name = "enabled", havingValue = "true")
    OAuth2TokenGenerator<OAuth2Token> oauth2TokenGenerator(
            JwtEncoder jwtEncoder,
            IamTokenClaimsResolver claimsResolver,
            OidcTokenProperties properties
    ) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(new IamJwtCustomizer(
                claimsResolver, properties.getAccessTokenAudience()));
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2RefreshTokenGenerator());
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new JdbcOAuth2AuthorizationConsentStore(jdbcTemplate, objectMapper);
    }

    @Bean
    AuthorizationSessionResolver authorizationSessionResolver() {
        return new IamSessionAuthorizationResolver();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rigour.iam.oidc.authorization-attributes",
            name = "enabled",
            havingValue = "true"
    )
    AuthorizationAttributesCipher authorizationAttributesCipher(OidcAuthorizationProperties properties) {
        return new AesGcmAuthorizationAttributesCipher(
                properties.getActiveKeyVersion(), properties.decodeKeys());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rigour.iam.oidc.authorization-attributes",
            name = "enabled",
            havingValue = "true"
    )
    OAuth2AuthorizationService oauth2AuthorizationService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            PlatformTransactionManager transactionManager,
            AuthorizationAttributesCipher attributesCipher,
            AuthorizationSessionResolver sessionResolver,
            IdentifierGenerator identifierGenerator,
            org.springframework.beans.factory.ObjectProvider<SignedIdTokenDecoder> idTokenDecoderProvider
    ) {
        return new JdbcOAuth2AuthorizationStore(
                jdbcTemplate,
                registeredClientRepository,
                transactionManager,
                attributesCipher,
                sessionResolver,
                identifierGenerator,
                idTokenDecoderProvider.getIfAvailable()
        );
    }
}
