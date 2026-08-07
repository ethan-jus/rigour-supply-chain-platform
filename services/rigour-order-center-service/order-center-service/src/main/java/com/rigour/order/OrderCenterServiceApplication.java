package com.rigour.order;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-order-center-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
@EnableScheduling
public class OrderCenterServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(OrderCenterServiceApplication.class, "订单中心服务", args);
    }
}
