package com.rigour.gateway;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway 进程入口。
 * 提供静态路由、IAM JWT资源服务器校验和下游可信身份上下文；领域权限仍由各服务负责。
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(GatewayApplication.class, "API网关", args);
    }
}
