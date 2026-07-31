package com.rigour.platform.startup;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** 统一启动Spring服务，并在完全就绪后输出可识别的成功标识。 */
public final class ServiceApplicationLauncher {

    private ServiceApplicationLauncher() {
    }

    public static ConfigurableApplicationContext run(
            Class<?> applicationClass,
            String serviceDisplayName,
            String[] args
    ) {
        ConfigurableApplicationContext context = SpringApplication.run(applicationClass, args);
        Environment environment = context.getEnvironment();
        String applicationName = environment.getProperty(
                "spring.application.name", applicationClass.getSimpleName());
        String port = environment.getProperty(
                "local.server.port", environment.getProperty("server.port", "N/A"));
        String[] activeProfiles = environment.getActiveProfiles();
        String profiles = activeProfiles.length == 0
                ? String.join(",", environment.getDefaultProfiles())
                : String.join(",", Arrays.asList(activeProfiles));
        Logger logger = LoggerFactory.getLogger(applicationClass);
        logger.info(
                "✅✅✅ [{}] 启动成功 | application={} | port={} | profiles={} ✅✅✅",
                serviceDisplayName, applicationName, port, profiles);
        return context;
    }
}
