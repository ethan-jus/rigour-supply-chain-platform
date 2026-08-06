package com.rigour.sales;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-sales-work-service 进程入口。
 * 当前已经建立V1领域Schema和基础设施依赖，具体打卡、拜访、规则、事件与查询用例仍按纵向切片实现。
 * 上下文启动成功不代表共享DEV Flyway、飞书真机、COS或跨服务闭环已经验收。
 */
@SpringBootApplication
public class SalesWorkServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(SalesWorkServiceApplication.class, "销售工作服务", args);
    }
}
