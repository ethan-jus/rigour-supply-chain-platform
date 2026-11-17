package com.rigour.order.infrastructure.persistence.repository;

/** MySQL Connector/J 可将 TINYINT(1) 读成 Boolean，其他驱动或配置可能读成数字。 */
final class JdbcFlags {
    private JdbcFlags() {}

    static boolean isSet(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.intValue() != 0;
        throw new IllegalStateException("数据库标记字段类型无效");
    }
}
