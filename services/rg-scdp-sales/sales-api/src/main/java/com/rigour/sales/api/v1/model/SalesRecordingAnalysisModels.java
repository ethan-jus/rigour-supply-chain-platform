package com.rigour.sales.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SalesRecordingAnalysisModels {
    private SalesRecordingAnalysisModels() {}
    public record Segment(long startMs, long endMs, String text, Integer speakerId) {}
    public record Clip(UUID clipId, int clipIndex, String status, String transcript, List<Segment> segments,
                       String summaryStatus, String summary, String summaryModel, String errorCode, Instant updatedAt) {}
    public record Speaker(int id,String name,String role) {}
    public record Visit(UUID visitId, boolean processingEnabled, List<Clip> clips,List<Speaker> speakers) {}
}
