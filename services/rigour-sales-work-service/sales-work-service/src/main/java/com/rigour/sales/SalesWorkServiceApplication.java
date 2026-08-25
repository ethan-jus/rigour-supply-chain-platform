package com.rigour.sales;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * rigour-sales-work-service 进程入口。
 * 已落地销售上下文、CRM门店投影、外勤考勤、当日轨迹、拜访结果和录音上传事实闭环。
 * AI验证/复核与HR/BI消费者仍按独立纵向切片实现。
 * 上下文启动成功不代表共享DEV Flyway、飞书真机、COS或跨服务闭环已经验收。
 */
@SpringBootApplication
@EnableScheduling
public class SalesWorkServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(SalesWorkServiceApplication.class, "销售工作服务", args);
    }
}
