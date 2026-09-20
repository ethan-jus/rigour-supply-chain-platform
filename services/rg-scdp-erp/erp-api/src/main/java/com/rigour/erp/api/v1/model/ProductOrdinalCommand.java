package com.rigour.erp.api.v1.model;

/**
 * ERP 商品列表就地修改排序值命令。
 *
 * <p>只改排序值，不触达商品其他字段；必须携带 revision 参与乐观锁，
 * 避免覆盖同一条商品正在进行的其他编辑。</p>
 */
public record ProductOrdinalCommand(Integer ordinal, Integer revision) {
}
