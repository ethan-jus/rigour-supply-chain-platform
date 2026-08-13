package com.rigour.merchant.infrastructure.config;

import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient;
import com.rigour.merchant.application.port.out.DhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.application.service.CrmSyncScheduleProperties;
import com.rigour.merchant.infrastructure.integration.HttpDhbCrmMasterDataClient;
import com.rigour.merchant.infrastructure.integration.HttpDhbCrmSyncTargetDiscoveryClient;
import com.rigour.merchant.infrastructure.persistence.mapper.AddressMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.ContactMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmQueryMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncCheckpointMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncLockMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CrmSyncRunMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerAreaMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerPolicyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerProfileMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.CustomerTypeMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.ExternalStaffMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.PartyRoleMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SalesAssignmentMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceBindingMapper;
import com.rigour.merchant.infrastructure.persistence.mapper.SourceIdentityAliasMapper;
import com.rigour.merchant.infrastructure.persistence.repository.MybatisPlusCrmRepository;
import com.rigour.shared.context.TrustedContextSigner;
import java.time.Clock;
import java.time.Duration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** CRM 基础设施装配；只写 CRM Schema，订货宝客户端仍只存在于 Integration。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@MapperScan("com.rigour.merchant.infrastructure.persistence.mapper")
@EnableConfigurationProperties(CrmSyncScheduleProperties.class)
public class CrmInfrastructureConfiguration {

    @Bean
    Clock crmClock() {
        return Clock.systemUTC();
    }

    @Bean
    SimpleClientHttpRequestFactory crmIntegrationRequestFactory(
            @Value("${rigour.integration.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${rigour.integration.http.read-timeout:30s}") Duration readTimeout) {
        positive(connectTimeout, "connect-timeout");
        positive(readTimeout, "read-timeout");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Bean
    DhbCrmMasterDataClient dhbCrmMasterDataClient(
            TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbCrmMasterDataClient(
                RestClient.builder().requestFactory(requestFactory), signer, integrationBaseUrl);
    }

    @Bean
    DhbCrmSyncTargetDiscoveryClient dhbCrmSyncTargetDiscoveryClient(
            TrustedContextSigner signer,
            @Value("${rigour.integration.base-url:http://localhost:26882}") String integrationBaseUrl,
            SimpleClientHttpRequestFactory requestFactory) {
        return new HttpDhbCrmSyncTargetDiscoveryClient(
                RestClient.builder().requestFactory(requestFactory), signer, integrationBaseUrl);
    }

    @Bean
    MybatisPlusCrmRepository crmRepository(
            CustomerTypeMapper customerTypeMapper, CustomerAreaMapper customerAreaMapper,
            PartyMapper partyMapper, PartyRoleMapper partyRoleMapper,
            CustomerProfileMapper customerProfileMapper, CustomerPolicyMapper customerPolicyMapper,
            ContactMapper contactMapper, AddressMapper addressMapper,
            ExternalStaffMapper externalStaffMapper, SalesAssignmentMapper assignmentMapper,
            CrmSyncRunMapper syncRunMapper, CrmSyncCheckpointMapper checkpointMapper,
            CrmSyncLockMapper lockMapper, SourceBindingMapper bindingMapper,
            SourceIdentityAliasMapper aliasMapper, CrmQueryMapper queryMapper,
            ObjectMapper objectMapper, Clock crmClock) {
        return new MybatisPlusCrmRepository(customerTypeMapper, customerAreaMapper,
                partyMapper, partyRoleMapper, customerProfileMapper, customerPolicyMapper,
                contactMapper, addressMapper, externalStaffMapper, assignmentMapper,
                syncRunMapper, checkpointMapper, lockMapper, bindingMapper, aliasMapper,
                queryMapper, objectMapper, crmClock);
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("CRM Integration " + name + " 必须在1ms到120s之间");
        }
    }
}
