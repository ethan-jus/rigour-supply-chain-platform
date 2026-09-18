package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class Wgs84Gcj02ConverterTest {

    private final Wgs84Gcj02Converter converter = new Wgs84Gcj02Converter();

    @Test
    void convertsMainlandGpsLocallyWithoutAnUpstreamDependency() {
        Wgs84Gcj02Converter.Coordinates converted = converter.convert(
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"));

        assertThat(converted.longitude()).isEqualByComparingTo("116.403372");
        assertThat(converted.latitude()).isEqualByComparingTo("39.917931");
    }

    @Test
    void leavesCoordinatesOutsideMainlandChinaUnchanged() {
        Wgs84Gcj02Converter.Coordinates converted = converter.convert(
                new BigDecimal("-122.419400"), new BigDecimal("37.774900"));

        assertThat(converted.longitude()).isEqualByComparingTo("-122.419400");
        assertThat(converted.latitude()).isEqualByComparingTo("37.774900");
    }
}
