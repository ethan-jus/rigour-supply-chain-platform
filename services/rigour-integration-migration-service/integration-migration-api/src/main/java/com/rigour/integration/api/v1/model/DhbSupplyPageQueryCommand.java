package com.rigour.integration.api.v1.model;

/** Integration 内部订货宝供应链分页请求。 */
public record DhbSupplyPageQueryCommand(
        /** 零基起始偏移，未传时默认为 0。 */ Integer begin,
        /** 每页数量，未传时默认为 200。 */ Integer step) {
    public int effectiveBegin() {
        return begin == null ? 0 : begin;
    }

    public int effectiveStep() {
        return step == null ? 200 : step;
    }
}
