package com.rigour.sales;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-sales-work-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class SalesWorkServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(SalesWorkServiceApplication.class, "销售工作服务", args);
    }
}
