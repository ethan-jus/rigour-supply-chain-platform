package com.rigour.integration.infrastructure.lease;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 订货宝连接器分布式租约参数。 */
@ConfigurationProperties(prefix = "rigour.integration.connector-lease")
public final class DhbConnectorLeaseProperties {
    private Duration ttl = Duration.ofMinutes(2);

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration value) { ttl = value; }

    /** 启动时拒绝会导致失锁或长时间脏锁的参数。 */
    public void validate() {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0
                || ttl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalStateException("订货宝连接器租约ttl必须在30秒到30分钟之间");
        }
    }
}
