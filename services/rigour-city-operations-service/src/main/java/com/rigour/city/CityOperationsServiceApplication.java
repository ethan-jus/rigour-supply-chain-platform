package com.rigour.city;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-city-operations-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class CityOperationsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CityOperationsServiceApplication.class, args);
    }
}
