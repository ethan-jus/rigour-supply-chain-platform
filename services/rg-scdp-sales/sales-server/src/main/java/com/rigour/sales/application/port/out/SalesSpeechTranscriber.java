package com.rigour.sales.application.port.out;

import java.util.List;
import java.util.UUID;
import com.rigour.sales.application.port.out.SalesAudioAiClient.TranscriptSegment;

/** Speech recognition only; no business recommendation or invented confidence score. */
public interface SalesSpeechTranscriber {
    Result transcribe(UUID tenantId, String objectKey, List<SpeakerProfile> speakers);
    record SpeakerProfile(int id, List<Double> embedding) {}
    record Result(String transcript, List<TranscriptSegment> segments, String model, String requestId,
                  String speakerModel, List<SpeakerProfile> speakerProfiles) {}
}
