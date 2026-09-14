package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.*;

import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.AdminReadOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 排序表达式的边界验证：有限字段、顺序保留和风险查询依赖都在 SQL 生成之前确定。 */
class TemporaryCheckinCombinedSortOptionsTest {
    @Test void defaultsAndLegacyConstructorsRemainCompatible() {
        assertThat(AdminReadOptions.defaults().orderBy()).isEqualTo(" ORDER BY s.submitted_at IS NULL ASC, s.submitted_at DESC, s.id DESC");
        var old=new AdminReadOptions(null,null,null,"cityName","asc");
        assertThat(old.orderBy()).isEqualTo(" ORDER BY s.city IS NULL ASC, s.city ASC, s.submitted_at IS NULL ASC, s.submitted_at DESC, s.id DESC");
        assertThat(old.sortDescription()).isEqualTo("城市升序 → 默认打卡时间降序");
        assertThat(old.withSorts(null)).isSameAs(old);assertThat(old.withSorts(List.of())).isSameAs(old);
    }
    @Test void combinesPriorityKeepsSameNamedSalespeopleTogetherAndPreservesScope() {
        var requested=new ArrayList<>(List.of("salespersonName:asc","completedAt:desc"));
        var options=AdminReadOptions.defaults().withSorts(requested);requested.clear();
        assertThat(options.orderBy()).isEqualTo(" ORDER BY s.salesperson_name_snapshot IS NULL ASC, s.salesperson_name_snapshot ASC, s.salesperson_id ASC, s.submitted_at IS NULL ASC, s.submitted_at DESC, s.id DESC");
        assertThat(options.withScope("北京").sorts()).isEqualTo(options.sorts());
        assertThat(options.sortDescription()).isEqualTo("销售升序 → 打卡时间降序");
        assertThat(options.sortBy()).isEqualTo("salespersonName");assertThat(options.sortDirection()).isEqualTo("asc");
    }
    @ParameterizedTest @ValueSource(strings={"deviceId","deviceVisitCount","deviceSalespersonCount","audioDuplicateCount"})
    void secondaryRiskSortTriggersRiskCte(String field) {
        var options=AdminReadOptions.defaults().withSorts(List.of("cityName:asc",field+":desc"));
        assertThat(options.needsRisk()).isTrue();assertThat(options.hasRiskFilters()).isFalse();
        assertThat(options.orderBy()).contains("rf.").startsWith(" ORDER BY s.city");
    }
    @Test void acceptsExactlyEightDistinctSupportedFieldsAndRejectsOverLimitOrDuplicates() {
        var all=List.of("completedAt:asc","cityName:desc","salespersonName:asc","storeName:desc","deviceId:asc",
                "deviceVisitCount:desc","deviceSalespersonCount:asc","audioDuplicateCount:desc");
        assertThat(AdminReadOptions.defaults().withSorts(all).sorts()).hasSize(8);
        assertThatThrownBy(()->AdminReadOptions.defaults().withSorts(java.util.Collections.nCopies(9,"completedAt:asc")))
                .isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("8");
        assertThatThrownBy(()->AdminReadOptions.defaults().withSorts(List.of("completedAt:asc","completedAt:desc")))
                .isInstanceOf(TemporaryCheckinException.class).hasMessageContaining("重复");
    }
    @ParameterizedTest @ValueSource(strings={"","completedAt","completedAt:",":asc","completedAt:ASC","completedAt:asc:desc",
            "completedAt:asc,cityName:desc","id:asc","cityName:desc;DROP TABLE x","cityName :asc"})
    void rejectsMalformedExpressions(String sort) {
        assertThatThrownBy(()->AdminReadOptions.defaults().withSorts(List.of(sort))).isInstanceOf(TemporaryCheckinException.class);
    }
    @Test void rejectsNullAndOverlongExpressions() {
        assertThatThrownBy(()->AdminReadOptions.defaults().withSorts(java.util.Collections.singletonList(null))).isInstanceOf(TemporaryCheckinException.class);
        assertThatThrownBy(()->AdminReadOptions.defaults().withSorts(List.of("x".repeat(65)))).isInstanceOf(TemporaryCheckinException.class);
    }
}
