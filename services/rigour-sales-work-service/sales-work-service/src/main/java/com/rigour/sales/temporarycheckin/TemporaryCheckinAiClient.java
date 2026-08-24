package com.rigour.sales.temporarycheckin;

import java.math.BigDecimal;
import java.net.URI;

/** 临时打卡录音异步转写与摘要客户端。 */
public interface TemporaryCheckinAiClient {

    /** 使用短时可访问的 HTTPS 音频地址创建转写任务。 */
    AsrTask createTranscriptionTask(URI audioUrl);

    /** 轮询一个已创建的转写任务。 */
    AsrTaskStatus describeTranscriptionTask(String taskId);

    /** 根据转写正文生成有长度上限的销售拜访摘要。 */
    SummaryResult summarize(String transcript);

    /** 转写任务创建结果。 */
    record AsrTask(String taskId, String requestId) {
    }

    /** 转写任务的统一状态。 */
    enum AsrState {
        WAITING,
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    /**
     * 转写任务轮询结果。
     *
     * @param transcript 仅在成功时有值
     * @param errorCode 仅在失败时有值，不包含供应商原始错误正文
     * @param audioDurationSeconds 音频时长，单位秒
     */
    record AsrTaskStatus(
            String taskId,
            AsrState state,
            String transcript,
            String errorCode,
            String requestId,
            BigDecimal audioDurationSeconds) {
    }

    /** 摘要结果及供追溯使用的模型和请求编号。 */
    record SummaryResult(String summary, String model, String requestId) {
    }

    /** 不携带凭据、媒体地址或转写正文的客户端错误。 */
    final class AiClientException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String code;

        public AiClientException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
