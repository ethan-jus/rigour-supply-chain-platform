-- perceptual_hash 旧字段现存储版本化的解码 PCM 精确指纹，支持同租户录音重放检测。
CREATE INDEX idx_sales_recording_clip_decoded_content_hash
    ON sales_recording_clip (tenant_id, perceptual_hash);
