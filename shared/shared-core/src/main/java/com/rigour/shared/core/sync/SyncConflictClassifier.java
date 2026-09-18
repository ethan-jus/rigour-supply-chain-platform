package com.rigour.shared.core.sync;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.web.client.RestClientResponseException;

/** 将本地或跨服务返回的稳定同步并发冲突识别为“本轮跳过”，避免按任务失败处理。 */
public final class SyncConflictClassifier {
    private static final String CODE = ErrorCode.SYNC_ALREADY_RUNNING.getCode();

    private SyncConflictClassifier() {
    }

    /**
     * @return 异常链中是否包含本地稳定错误码，或下游 HTTP 409 响应中的相同错误码
     */
    public static boolean isAlreadyRunning(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BusinessException business
                    && business.getErrorCode() == ErrorCode.SYNC_ALREADY_RUNNING) return true;
            if (current instanceof RestClientResponseException remote
                    && remote.getStatusCode().value() == 409
                    && remote.getResponseBodyAsString().contains(CODE)) return true;
            current = current.getCause();
        }
        return false;
    }
}
