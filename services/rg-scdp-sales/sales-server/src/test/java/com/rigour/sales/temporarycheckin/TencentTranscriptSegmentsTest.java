package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TencentTranscriptSegmentsTest {
    @Test
    void keepsProviderOffsetsWithoutInventingSpeakersOrInvalidPositions() {
        var details=JsonMapper.builder().build().readTree("""
                [{"StartMs":20,"EndMs":2380,"FinalSentence":"需要更换台呢","SpeakerId":0},
                 {"StartMs":2400,"EndMs":3100,"FinalSentence":"下周送货"},
                 {"StartMs":4000,"EndMs":3000,"FinalSentence":"错误时间"},
                 {"StartMs":-1,"EndMs":3000,"FinalSentence":"缺失时间"}]
                """);
        var result=TencentTemporaryCheckinAiClient.parseSegments(details);
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().startMs()).isEqualTo(20);
        assertThat(result.getFirst().endMs()).isEqualTo(2380);
        assertThat(result.get(1).speakerId()).isNull();
    }
}
