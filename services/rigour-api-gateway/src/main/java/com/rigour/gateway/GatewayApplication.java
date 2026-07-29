package com.rigour.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway 进程入口。
 * 当前骨架只提供静态本地路由和公共上下文，不包含生产级认证、限流、服务发现或信任边界校验。
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
