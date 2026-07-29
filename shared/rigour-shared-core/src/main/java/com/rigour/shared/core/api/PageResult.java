package com.rigour.shared.core.api;

import java.util.List;

/**
 * 从 1 开始计页的标准分页结果。
 */
public record PageResult<T>(List<T> items, int page, int pageSize, long total) {

    public PageResult {
        items = List.copyOf(items);
        if (page < 1 || pageSize < 1 || total < 0) {
            throw new IllegalArgumentException("分页参数必须为非负值，且 page/pageSize 从 1 开始");
        }
    }
}
