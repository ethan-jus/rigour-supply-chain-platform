package com.rigour.platform.http;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** 让所有通过 RestClient.Builder 构建的出站客户端自动获得注册中心服务名寻址能力。 */
@AutoConfiguration(after = RestClientAutoConfiguration.class)
@ConditionalOnClass({RestClient.class, DiscoveryClient.class, RestClientAutoConfiguration.class})
public class ServiceAddressingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ServiceAddressResolver serviceAddressResolver(
            ObjectProvider<DiscoveryClient> discoveryClient,
            ObjectProvider<LoadBalancerClient> loadBalancerClient) {
        return new ServiceAddressResolver(discoveryClient, loadBalancerClient);
    }

    @Bean
    RestClientCustomizer serviceAddressRestClientCustomizer(ServiceAddressResolver resolver) {
        return builder -> builder.requestInterceptor(resolver);
    }
}
