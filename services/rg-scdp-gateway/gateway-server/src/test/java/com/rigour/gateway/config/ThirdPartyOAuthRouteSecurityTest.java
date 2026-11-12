package com.rigour.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.*;

class ThirdPartyOAuthRouteSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc @Import(GatewaySecurityConfiguration.class)
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean Echo controller(){return new Echo();}
    }
    @RestController static class Echo {
        @RequestMapping("/api/{*path}") public String ok(){return "controller-reached";}
    }
    @Test void onlyExactOAuthGetEntrypointsArePublicEvenWhenGatewaySecurityIsUnconfigured() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
            for(String entry:new String[]{"start","callback"}) {
                String path="/api/v1/integration/huolala/oauth/"+entry;
                assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(200);
                assertThat(mvc.perform(post(path)).andReturn().getResponse().getStatus()).isNotEqualTo(200);
                assertThat(mvc.perform(get(path+"/extra")).andReturn().getResponse().getStatus()).isNotEqualTo(200);
            }
            for(String path:new String[]{"/api/v1/integration/huolala/connection","/api/v1/integration/thirdparty/HUOLALA/authorizations/id","/api/v1/integration/huolala/sync"})
                assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        }
    }
}
