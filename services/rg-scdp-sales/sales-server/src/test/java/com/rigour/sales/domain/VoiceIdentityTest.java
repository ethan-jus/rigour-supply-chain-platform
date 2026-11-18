package com.rigour.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceIdentityTest {
    private List<Double> vector(double x,double y) {
        var result=new ArrayList<Double>(java.util.Collections.nCopies(192,0.0));
        result.set(0,x);result.set(1,y);return result;
    }
    @Test void identifiesRegisteredColleagueRatherThanAssumingVisitOwner() {
        assertThat(VoiceIdentity.match(vector(0,1),List.of(vector(1,0),vector(0,1)))).isEqualTo(1);
    }
    @Test void rejectsUnregisteredAndAmbiguousSpeakers() {
        assertThat(VoiceIdentity.match(vector(0,1),List.of(vector(1,0)))).isNull();
        assertThat(VoiceIdentity.match(vector(1,0),List.of(vector(1,0),vector(.99,Math.sqrt(1-.99*.99))))).isNull();
        assertThat(VoiceIdentity.match(vector(1,0),List.of())).isNull();
    }
    @Test void rejectsMalformedFeaturesInsteadOfInventingAnIdentity() {
        assertThat(VoiceIdentity.valid(List.of(1.0))).isFalse();
        assertThat(VoiceIdentity.valid(vector(Double.NaN,0))).isFalse();
        assertThat(VoiceIdentity.valid(vector(0,0))).isFalse();
        assertThat(VoiceIdentity.match(vector(1,0),List.of(vector(Double.POSITIVE_INFINITY,0)))).isNull();
    }
}
