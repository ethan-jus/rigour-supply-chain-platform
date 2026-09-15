package com.rigour.hr.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 防止将离职、停用及未知来源全部归成在职或同一停用状态。 */
class EmploymentStatusTest {
    @ParameterizedTest
    @CsvSource({"在职,ACTIVE", "ACTIVE,ACTIVE", "离职,LEFT", "LEFT,LEFT", "停用,INACTIVE",
            "INACTIVE,INACTIVE", "冻结,INACTIVE", "待入职,PENDING", "未核实,PENDING"})
    void keepsSeparationDistinctFromDisabled(String source, String expected) {
        assertThat(EmploymentStatus.fromSource(source)).isEqualTo(expected);
    }
    @ParameterizedTest @NullAndEmptySource
    void missingStatusDoesNotInventActiveEmployment(String source) {
        assertThat(EmploymentStatus.fromSource(source)).isEqualTo("PENDING");
    }
}
