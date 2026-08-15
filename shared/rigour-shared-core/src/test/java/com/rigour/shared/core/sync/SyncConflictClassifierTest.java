package com.rigour.shared.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class SyncConflictClassifierTest {
    @Test
    void recognizesLocalStableConflictThroughCauseChain() {
        RuntimeException wrapped = new RuntimeException(new BusinessException(
                ErrorCode.SYNC_ALREADY_RUNNING, "already running", List.of()));

        assertThat(SyncConflictClassifier.isAlreadyRunning(wrapped)).isTrue();
    }

    @Test
    void recognizesRemote409OnlyWhenStableCodeIsPresent() {
        HttpClientErrorException conflict = HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                "{\"code\":\"SYNC_ALREADY_RUNNING\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        HttpClientErrorException unrelated = HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                "{\"code\":\"OTHER\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(SyncConflictClassifier.isAlreadyRunning(conflict)).isTrue();
        assertThat(SyncConflictClassifier.isAlreadyRunning(unrelated)).isFalse();
    }
}
