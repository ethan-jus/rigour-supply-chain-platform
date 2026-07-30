package com.rigour.tenant.iam.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** IAM基础设施装配入口；Mapper只允许扫描本服务自己的持久化包。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.rigour.tenant.iam.infrastructure.persistence.mapper")
public final class IamInfrastructureConfiguration {
}
