package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitEvidenceSummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPhotoEvidenceView;
import com.rigour.sales.application.port.out.SalesWorkEvidenceRepository;
import com.rigour.sales.application.port.out.SalesWorkEvidenceRepository.PhotoEvidenceRow;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitSnapshot;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitTargetSnapshot;
import com.rigour.sales.infrastructure.config.SalesEvidenceProperties;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 现场门头照证据用例。客户端只能通过飞书相机入口采集；服务端校验字节、时间、位置、围栏和幂等。
 * captureSource 不是设备级密码学证明，照片是否真正包含门头仍需后续图像分析或主管抽检。
 */
@Service
public class SalesWorkEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(SalesWorkEvidenceService.class);
    private static final String CAMERA_SOURCE = "FEISHU_CAMERA";
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final SalesWorkEvidenceRepository evidenceRepository;
    private final SalesWorkVisitRepository visitRepository;
    private final SalesWorkQueryRepository queryRepository;
    private final SalesWorkContextService contextService;
    private final SalesWorkVisitAssessmentService assessmentService;
    private final FileStorage fileStorage;
    private final SalesEvidenceProperties properties;
    private final AuditSink auditSink;
    private final Clock clock;

    public SalesWorkEvidenceService(
            SalesWorkEvidenceRepository evidenceRepository,
            SalesWorkVisitRepository visitRepository,
            SalesWorkQueryRepository queryRepository,
            SalesWorkContextService contextService,
            SalesWorkVisitAssessmentService assessmentService,
            FileStorage fileStorage,
            SalesEvidenceProperties properties,
            AuditSink auditSink,
            Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.visitRepository = visitRepository;
        this.queryRepository = queryRepository;
        this.contextService = contextService;
        this.assessmentService = assessmentService;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Transactional
    public VisitPhotoEvidenceView uploadStorefrontPhoto(
            UUID visitId, MultipartFile file, String clientEvidenceId, String captureSource,
            Instant capturedAt, BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters) {
        CallerIdentity caller = requireCaller("sales:evidence:own:write");
        String normalizedClientId = required(clientEvidenceId, "clientEvidenceId", 128);
        if (!CAMERA_SOURCE.equals(captureSource)) {
            throw invalid("门头照只允许通过飞书手机相机现场拍摄");
        }
        if (visitId == null || file == null || file.isEmpty()) throw invalid("门头照片不能为空");
        if (properties.getMaxPhotoBytes() <= 0 || file.getSize() > properties.getMaxPhotoBytes()) {
            throw invalid("门头照片超过大小限制 " + properties.getMaxPhotoBytes() + " 字节");
        }
        validateCoordinates(longitude, latitude, accuracyMeters);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        VisitSnapshot visit = visitRepository.findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        if (!"CHECKED_IN".equals(visit.status()) && !"CHECKED_OUT".equals(visit.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                    "当前拜访状态不允许拍摄门头照", List.of());
        }
        validateCaptureTime(visit, capturedAt, receivedAt);
        VisitTargetSnapshot target = visitRepository.findTargetSnapshot(caller.tenantId(), visitId)
                .orElseThrow(() -> invalid("拜访目标快照不存在"));
        var policy = queryRepository.findVisitPolicy(caller.tenantId(), visit.visitPolicyVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));
        double distance = distanceMeters(longitude, latitude, target.longitude(), target.latitude());
        if (distance > policy.checkInRadiusMeters()) {
            throw new BusinessException(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS,
                    "拍照位置距离门店 " + Math.round(distance) + " 米，超过规则允许的 "
                            + policy.checkInRadiusMeters() + " 米", List.of());
        }

        byte[] bytes = readBytes(file);
        String mediaType = normalizeMediaType(file.getContentType());
        validateImageSignature(mediaType, bytes);
        validateDecodableImage(bytes);
        String hash = sha256Hex(bytes);
        var existing = evidenceRepository.findPhotoByClientEvidenceId(
                caller.tenantId(), visitId, normalizedClientId);
        if (existing.isPresent()) {
            PhotoEvidenceRow row = existing.get();
            if (!Objects.equals(row.contentHash(), hash)) {
                throw invalid("clientEvidenceId已被不同照片内容使用");
            }
            return view(row);
        }

        UUID evidenceId = UUID.nameUUIDFromBytes((caller.tenantId() + ":" + visitId + ":photo:"
                + normalizedClientId).getBytes(StandardCharsets.UTF_8));
        String extension = "image/png".equals(mediaType) ? ".png" : ".jpg";
        String objectKey = caller.tenantId() + "/visits/" + visitId + "/photos/"
                + evidenceId + "-" + hash + extension;
        try {
            fileStorage.put(new FileMetadata(caller.tenantId().toString(), objectKey,
                    file.getOriginalFilename() == null ? objectKey : file.getOriginalFilename(),
                    mediaType, bytes.length, hash, OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC)),
                    new ByteArrayInputStream(bytes));
        } catch (RuntimeException error) {
            throw storageFailure("门头照片存储失败", error);
        }
        try {
            evidenceRepository.insertStorefrontPhoto(evidenceId, caller.tenantId(), visitId,
                    normalizedClientId, objectKey, mediaType, bytes.length, hash, CAMERA_SOURCE,
                    capturedAt, longitude, latitude, accuracyMeters, BigDecimal.valueOf(distance),
                    caller.userId(), receivedAt);
        } catch (DataIntegrityViolationException duplicate) {
            var concurrent = evidenceRepository.findPhotoByClientEvidenceId(
                    caller.tenantId(), visitId, normalizedClientId);
            if (concurrent.isPresent() && Objects.equals(concurrent.get().contentHash(), hash)) {
                return view(concurrent.get());
            }
            deleteUnreferencedObject(caller.tenantId(), objectKey);
            throw invalid("clientEvidenceId已被不同照片内容使用");
        }
        appendAudit(caller, visitId, evidenceId, bytes.length, distance);
        if ("CHECKED_OUT".equals(visit.status())) assessmentService.assess(caller, visitId);
        return evidenceRepository.findPhotoByClientEvidenceId(caller.tenantId(), visitId, normalizedClientId)
                .map(SalesWorkEvidenceService::view)
                .orElseThrow(() -> new IllegalStateException("门头照写入后不可见"));
    }

    public VisitEvidenceSummaryView evidence(UUID visitId) {
        CallerIdentity caller = requireCaller("sales:evidence:own:read");
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        VisitSnapshot visit = visitRepository.findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        var policy = queryRepository.findVisitPolicy(caller.tenantId(), visit.visitPolicyVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));
        List<VisitPhotoEvidenceView> photos = evidenceRepository
                .findStorefrontPhotos(caller.tenantId(), visitId).stream()
                .map(SalesWorkEvidenceService::view)
                .toList();
        int required = Math.max(1, policy.requiredPhotoCount());
        long accepted = photos.stream()
                .filter(photo -> "TECHNICALLY_VERIFIED".equals(photo.evidenceStatus()))
                .count();
        return new VisitEvidenceSummaryView(visitId, required, photos.size(), accepted >= required, photos);
    }

    private static VisitPhotoEvidenceView view(PhotoEvidenceRow row) {
        return new VisitPhotoEvidenceView(row.id(), row.visitId(), row.clientEvidenceId(),
                row.evidenceRole(), row.captureSource(), row.capturedAt(), row.mediaType(),
                row.objectSizeBytes(), row.contentHash(), row.longitude(), row.latitude(),
                row.accuracyMeters(), row.distanceToTargetMeters(), row.evidenceStatus(),
                row.serverReceivedAt());
    }

    private static void validateCaptureTime(VisitSnapshot visit, Instant capturedAt, Instant receivedAt) {
        if (capturedAt == null || capturedAt.isBefore(visit.checkedInAt().minusSeconds(60))) {
            throw invalid("门头照拍摄时间早于本次拜访");
        }
        Instant latest = visit.checkedOutAt() == null ? receivedAt.plusSeconds(60)
                : visit.checkedOutAt().plusSeconds(60);
        if (capturedAt.isAfter(latest)) throw invalid("门头照拍摄时间超出本次拜访范围");
    }

    private static void validateCoordinates(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters) {
        if (longitude == null || latitude == null || accuracyMeters == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || accuracyMeters.signum() < 0) {
            throw invalid("门头照定位无效");
        }
    }

    private static String normalizeMediaType(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase() : "";
        if (!SUPPORTED_IMAGE_TYPES.contains(normalized)) throw invalid("门头照只支持JPEG或PNG");
        return normalized;
    }

    private static void validateImageSignature(String mediaType, byte[] bytes) {
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        if (("image/jpeg".equals(mediaType) && !jpeg) || ("image/png".equals(mediaType) && !png)) {
            throw invalid("门头照文件内容与媒体类型不一致");
        }
    }

    private void validateDecodableImage(byte[] bytes) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw invalid("门头照无法解码");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("门头照不是可解码的JPEG或PNG");
            reader = readers.next();
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long pixels = Math.multiplyExact((long) width, height);
            if (width < properties.getMinPhotoWidth() || height < properties.getMinPhotoHeight()) {
                throw invalid("门头照分辨率过低，请重新拍摄清晰照片");
            }
            if (properties.getMaxPhotoPixels() <= 0 || pixels > properties.getMaxPhotoPixels()) {
                throw invalid("门头照像素过大，请使用飞书压缩相机重新拍摄");
            }
            BufferedImage decoded = reader.read(0);
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                throw invalid("门头照文件不完整，请重新拍摄");
            }
            decoded.flush();
        } catch (IOException | ArithmeticException error) {
            throw invalid("门头照文件损坏或无法完整解码");
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_EVIDENCE_STORAGE_FAILED,
                    "门头照片读取失败", List.of());
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }

    private static double distanceMeters(
            BigDecimal longitude, BigDecimal latitude,
            BigDecimal targetLongitude, BigDecimal targetLatitude) {
        double lat1 = Math.toRadians(latitude.doubleValue());
        double lat2 = Math.toRadians(targetLatitude.doubleValue());
        double dLat = Math.toRadians(targetLatitude.subtract(latitude).doubleValue());
        double dLng = Math.toRadians(targetLongitude.subtract(longitude).doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    private CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private void appendAudit(
            CallerIdentity caller, UUID visitId, UUID evidenceId, long size, double distance) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), "SALES_STOREFRONT_PHOTO_UPLOADED", "SALES_VISIT",
                visitId.toString(), Map.of(
                "evidenceId", evidenceId.toString(),
                "objectSizeBytes", Long.toString(size),
                "distanceMeters", Long.toString(Math.round(distance))),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) throw invalid(field + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw invalid(field + "长度不能超过" + maxLength);
        return normalized;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.SALES_EVIDENCE_INVALID, message, List.of());
    }

    private static BusinessException storageFailure(String message, RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.SALES_EVIDENCE_STORAGE_FAILED, message, List.of());
        exception.initCause(cause);
        return exception;
    }

    private void deleteUnreferencedObject(UUID tenantId, String objectKey) {
        try {
            fileStorage.delete(tenantId.toString(), objectKey);
        } catch (RuntimeException cleanupError) {
            log.warn("并发照片证据清理失败 tenantId={} reason={}",
                    tenantId, cleanupError.getClass().getSimpleName());
        }
    }
}
