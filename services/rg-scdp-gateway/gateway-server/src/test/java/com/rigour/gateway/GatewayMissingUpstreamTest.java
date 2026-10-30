package com.rigour.gateway;

import com.rigour.shared.core.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GatewayMissingUpstreamTest {
    @Test
    void missingDiscoveredInstanceReturns503ThroughTheActualGatewayFilter() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String service : List.of("rigour-order-center-service", "rigour-analytics-bi-service")) {
                mvc.perform(get("/test/" + service))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                        .andExpect(jsonPath("$.message").value("服务暂不可用"));
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(GlobalExceptionHandler.class)
    static class TestConfiguration {
        @Bean
        LoadBalancerClientFactory loadBalancerClientFactory() {
            var factory = mock(LoadBalancerClientFactory.class);
            for (String service : List.of("rigour-order-center-service", "rigour-analytics-bi-service")) {
                when(factory.getProperties(service)).thenReturn(new LoadBalancerProperties());
                when(factory.getInstance(service, LoadBalancerClient.class)).thenReturn(mock(LoadBalancerClient.class));
            }
            return factory;
        }

        @Bean
        RouterFunction<ServerResponse> routes() {
            var builder = RouterFunctions.route();
            for (String service : List.of("rigour-order-center-service", "rigour-analytics-bi-service")) {
                builder.add(RouterFunctions.route()
                        .GET("/test/" + service, request -> ServerResponse.ok().body("must not forward"))
                        .filter(LoadBalancerFilterFunctions.lb(service))
                        .build());
            }
            return builder.build();
        }
    }
}
