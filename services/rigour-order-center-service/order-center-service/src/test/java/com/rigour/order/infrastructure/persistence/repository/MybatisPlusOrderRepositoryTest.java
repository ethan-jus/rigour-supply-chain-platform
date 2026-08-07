package com.rigour.order.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MybatisPlusOrderRepositoryTest {
    @Test
    void expandsOfficialStockUpAliasToStoredStatusValue() {
        assertEquals(List.of("pricing", "stockup", "stock_up", "shipped", "received"),
                MybatisPlusOrderRepository.sourceStatusValues("pricing,stock_up,shipped,received"));
    }

    @Test
    void acceptsStoredStockupAliasAsWell() {
        assertEquals(List.of("stockup", "stock_up"),
                MybatisPlusOrderRepository.sourceStatusValues("stockup"));
    }
}
