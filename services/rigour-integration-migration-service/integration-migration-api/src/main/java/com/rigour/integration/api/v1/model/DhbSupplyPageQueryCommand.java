package com.rigour.integration.api.v1.model;

import java.time.Instant;

/** Integration 内部订货宝供应链分页请求。 */
public record DhbSupplyPageQueryCommand(
        /** 零基起始偏移，未传时默认为 0。 */ Integer begin,
        /** 每页数量，未传时默认为 200。 */ Integer step,
        /** 可选来源业务时间窗口开始；只对已确认支持时间筛选的接口生效。 */ Instant from,
        /** 可选来源业务时间窗口结束；只对已确认支持时间筛选的接口生效。 */ Instant to) {
    public DhbSupplyPageQueryCommand {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("供应链同步窗口from和to必须同时提供");
        }
        if (from != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("供应链同步窗口from必须早于to");
        }
    }

    public DhbSupplyPageQueryCommand(Integer begin, Integer step) {
        this(begin, step, null, null);
    }

    public int effectiveBegin() {
        return begin == null ? 0 : begin;
    }

    public int effectiveStep() {
        return step == null ? 200 : step;
    }
}
