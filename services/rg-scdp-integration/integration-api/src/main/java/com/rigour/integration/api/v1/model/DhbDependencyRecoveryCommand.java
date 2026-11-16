package com.rigour.integration.api.v1.model;

import java.util.UUID;

/** 对一条待处理发货，选择业务已经补录的本系统资料；来源身份由服务端问题记录确定。 */
public record DhbDependencyRecoveryCommand(
        UUID connectorId,
        String sourceObjectType,
        String sourceId,
        Long targetId,
        Long productId,
        String requiredDataType,
        String requiredSourceId) {}
