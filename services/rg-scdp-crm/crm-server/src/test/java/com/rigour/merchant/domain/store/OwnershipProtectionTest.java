package com.rigour.merchant.domain.store;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class OwnershipProtectionTest {
    final OwnershipProtection.Policy p=new OwnershipProtection.Policy(24,7,60,48);
    final Instant t=Instant.parse("2026-10-01T00:00:00Z");
    @Test void firstVisitBoundaryUsesNaturalHours(){var w=OwnershipProtection.calculate(p,t,null,null);assertThat(w.deadline()).isEqualTo(t.plusSeconds(86400));assertThat(w.expired(w.deadline().minusNanos(1))).isFalse();assertThat(w.expired(w.deadline())).isTrue();}
    @Test void uncooperatedVisitProtectsSevenDays(){var visit=t.plusSeconds(3600);var w=OwnershipProtection.calculate(p,t,visit,null);assertThat(w.phase()).isEqualTo("FOLLOW_UP");assertThat(w.deadline()).isEqualTo(visit.plusSeconds(7*86400));}
    @Test void orderOrSubsequentVisitProtectsSixtyDays(){var order=t.plusSeconds(7200);var visit=t.plusSeconds(50*86400L);assertThat(OwnershipProtection.calculate(p,t,null,order).deadline()).isEqualTo(order.plusSeconds(60*86400L));assertThat(OwnershipProtection.calculate(p,t,visit,order).deadline()).isEqualTo(visit.plusSeconds(60*86400L));}
    @Test void revocationRecomputesFromOriginalFactsInsteadOfNow(){var visit=t.plusSeconds(3600);assertThat(OwnershipProtection.calculate(p,t,visit,null).deadline()).isEqualTo(visit.plusSeconds(7*86400L));assertThat(OwnershipProtection.calculate(p,t,null,null).deadline()).isEqualTo(t.plusSeconds(86400));}
    @Test void previousCycleFactsCannotCount(){assertThatThrownBy(()->OwnershipProtection.calculate(p,t,null,t.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);}
}
