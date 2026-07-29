package com.rigour.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-merchant-crm-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class MerchantCrmServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantCrmServiceApplication.class, args);
    }
}
