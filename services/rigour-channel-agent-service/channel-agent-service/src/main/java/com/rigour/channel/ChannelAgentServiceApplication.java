package com.rigour.channel;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-channel-agent-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class ChannelAgentServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(ChannelAgentServiceApplication.class, "渠道代理服务", args);
    }
}
