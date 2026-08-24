package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TencentCloudTc3SignerTest {

    @Test
    void producesDeterministicTc3AuthorizationForAsrRequest() {
        TencentCloudTc3Signer.SignedHeaders result = TencentCloudTc3Signer.sign(
                "asr.tencentcloudapi.com",
                "asr",
                "AKIDEXAMPLE",
                "SECRETEXAMPLE",
                "{\"TaskId\":522931820}",
                Instant.ofEpochSecond(1_599_142_563L));

        assertThat(result.timestamp()).isEqualTo("1599142563");
        assertThat(result.authorization()).isEqualTo(
                "TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2020-09-03/asr/tc3_request, "
                        + "SignedHeaders=content-type;host, "
                        + "Signature=ab794e2d594bdd74269152c3ade2fa5b768594a281d0c120a1876dee5db534f9");
    }
}
