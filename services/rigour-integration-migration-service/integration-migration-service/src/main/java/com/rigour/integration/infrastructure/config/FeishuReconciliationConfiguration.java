package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuOnlineCaptureStore;
import com.rigour.integration.application.service.feishu.FeishuReconciliationService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 在线对账独立装配，不接入导入运行器及业务投影链路。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeishuReconciliationProperties.class)
public class FeishuReconciliationConfiguration {
    @Bean
    FeishuReconciliationService feishuReconciliationService(FeishuReconciliationProperties properties,
            FeishuBitableClient client, FeishuOnlineCaptureStore store, ObjectMapper json, Clock clock) {
        return new FeishuReconciliationService(properties, client, store, json, clock);
    }
}
