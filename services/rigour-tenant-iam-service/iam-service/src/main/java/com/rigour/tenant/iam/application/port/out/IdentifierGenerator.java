package com.rigour.tenant.iam.application.port.out;

import java.util.UUID;

/** 生成跨进程持久化标识；当前基础设施实现必须产生UUIDv7。 */
@FunctionalInterface
public interface IdentifierGenerator {

    UUID nextId();
}
