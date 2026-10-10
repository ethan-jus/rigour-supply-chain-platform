package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaymentVoucherWriteGuardTest {
    @Test void removedEvidenceCannotReturnAndOtherPaymentsAndTenantsAreUnaffected() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:voucher_guard;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE order_payment_voucher_exclusion(tenant_id VARCHAR(36),payment_id BIGINT,voucher_key VARCHAR(500))");
        jdbc.update("INSERT INTO order_payment_voucher_exclusion VALUES('a',11,'cancelled')");
        var guard = new PaymentVoucherWriteGuard(jdbc);
        assertThat(guard.retainedKeys("a",11,List.of("cancelled","valid","valid","split-second")))
                .containsExactly("valid","split-second");
        assertThat(guard.retainedKeys("b",11,List.of("cancelled"))).containsExactly("cancelled");
        assertThat(guard.retainedKeys("a",12,List.of("cancelled"))).containsExactly("cancelled");
        assertThat(guard.retainedKeys("a",11,List.of("cancelled"))).isEmpty();
        assertThat(guard.retainedKeys("a",11,null)).isEmpty();
    }
}
