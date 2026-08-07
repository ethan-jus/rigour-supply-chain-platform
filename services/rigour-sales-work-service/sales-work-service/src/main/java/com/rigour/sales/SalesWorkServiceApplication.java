package com.rigour.sales;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-sales-work-service 进程入口。
 * 阶段 1、2 已落地销售上下文、CRM门店目标和外勤考勤事实闭环；拜访、录音、COS、AI、HR/BI消费者仍按后续纵向切片实现。
 * 上下文启动成功不代表共享DEV Flyway、飞书真机、COS或跨服务闭环已经验收。
 */
@SpringBootApplication
public class SalesWorkServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(SalesWorkServiceApplication.class, "销售工作服务", args);
    }
}
