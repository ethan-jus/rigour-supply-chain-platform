package com.rigour.collaboration.application.service;

import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentCompleteCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentInitCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentInitView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationCreateCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationListView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationMemberCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationMembersView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationSummaryView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DeviceRegisterCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DeviceView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DirectoryView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingCreateCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingJoinTokenCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingJoinTokenView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MessagePageView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MessageView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ReadCursorCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.RecallMessageCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.SendMessageCommand;
import com.rigour.collaboration.application.port.out.CollaborationStore;
import com.rigour.collaboration.application.port.out.CollaborationStore.ConversationRecord;
import com.rigour.collaboration.application.port.out.CollaborationStore.MemberRecord;
import com.rigour.collaboration.application.port.out.RealtimeEventPublisher;
import com.rigour.collaboration.application.port.out.RealtimeEventPublisher.CollaborationEvent;
import com.rigour.collaboration.infrastructure.config.CollaborationProperties;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 内部 IM 和会议用例；不依赖销售身份，所有事实从当前租户用户身份出发。 */
@Service
public class CollaborationService {
    private static final Set<String> CONVERSATION_TYPES = Set.of("DIRECT", "GROUP");
    private static final Set<String> MESSAGE_TYPES = Set.of("TEXT", "IMAGE", "FILE", "VOICE");
    private static final Set<String> MEMBER_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int MEETING_MAX_PARTICIPANTS = 100;
    private static final int SCREEN_SHARE_LIMIT = 1;

    private final CollaborationStore store;
    private final RealtimeEventPublisher realtimeEvents;
    private final LiveKitTokenService liveKitTokenService;
    private final CollaborationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CollaborationService(CollaborationStore store, RealtimeEventPublisher realtimeEvents,
                                LiveKitTokenService liveKitTokenService, CollaborationProperties properties,
                                ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.realtimeEvents = realtimeEvents;
        this.liveKitTokenService = liveKitTokenService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DirectoryView directory(String query, int limit) {
        requireIm();
        CallerIdentity actor = tenantActor();
        ensureSelfProfile(actor);
        int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        return new DirectoryView(store.searchDirectory(actor.tenantId(), trim(query), normalizedLimit));
    }

    @Transactional
    public ConversationSummaryView createConversation(ConversationCreateCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        ensureSelfProfile(actor);
        if (command == null) throw badRequest("会话参数不能为空");
        String type = upper(command.type());
        if (!CONVERSATION_TYPES.contains(type)) throw badRequest("会话类型无效");
        LinkedHashSet<UUID> members = normalizedMembers(command.memberIds(), actor.userId());
        if ("DIRECT".equals(type) && members.size() != 2) throw badRequest("单聊必须且只能包含两个成员");
        if ("GROUP".equals(type) && members.size() < 2) throw badRequest("群聊至少需要两个成员");
        if (members.size() > properties.maxGroupMembers()) throw badRequest("群成员超过服务端限制");

        String directKey = "DIRECT".equals(type) ? directKey(members) : null;
        if (directKey != null) {
            var existing = store.findDirectConversation(actor.tenantId(), directKey);
            if (existing.isPresent()) return store.listConversations(actor.tenantId(), actor.userId()).stream()
                    .filter(item -> item.id().equals(existing.get().id()))
                    .findFirst()
                    .orElseThrow(() -> conflict("单聊会话存在但当前用户不可见"));
        }
        String title = "GROUP".equals(type) ? requireText(command.title(), "群名称不能为空", 120) : null;
        ConversationRecord conversation = store.createConversation(UUID.randomUUID(), actor.tenantId(), type, title,
                actor.userId(), directKey, now());
        for (UUID memberId : members) {
            ensureProfile(actor.tenantId(), memberId);
            store.upsertMember(actor.tenantId(), conversation.id(), memberId,
                    memberId.equals(actor.userId()) ? "OWNER" : "MEMBER", now());
        }
        publish(actor.tenantId(), conversation.id(), "conversation.updated", null, conversation);
        return store.listConversations(actor.tenantId(), actor.userId()).stream()
                .filter(item -> item.id().equals(conversation.id()))
                .findFirst()
                .orElseThrow(() -> conflict("会话创建后不可见"));
    }

    public ConversationListView conversations() {
        requireIm();
        CallerIdentity actor = tenantActor();
        ensureSelfProfile(actor);
        return new ConversationListView(store.listConversations(actor.tenantId(), actor.userId()));
    }

    @Transactional
    public ConversationMembersView addMembers(UUID conversationId, ConversationMemberCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        ConversationRecord conversation = requireConversation(actor, conversationId, true);
        MemberRecord actorMember = requireActiveMember(actor, conversationId);
        if ("DIRECT".equals(conversation.type())) throw conflict("单聊不能维护成员");
        if (!Set.of("OWNER", "ADMIN").contains(actorMember.role())) throw forbidden("只有群主或管理员可以加人");
        if (command == null || command.userIds().isEmpty()) throw badRequest("成员不能为空");
        String role = upper(command.role());
        if (role == null) role = "MEMBER";
        if (!MEMBER_ROLES.contains(role) || "OWNER".equals(role)) throw badRequest("成员角色无效");
        long currentCount = store.activeMemberCount(actor.tenantId(), conversationId);
        if (currentCount + command.userIds().size() > properties.maxGroupMembers()) {
            throw badRequest("群成员超过服务端限制");
        }
        for (UUID userId : command.userIds()) {
            if (userId == null) throw badRequest("成员ID不能为空");
            ensureProfile(actor.tenantId(), userId);
            store.upsertMember(actor.tenantId(), conversationId, userId, role, now());
        }
        ConversationMembersView view = new ConversationMembersView(store.listMembers(actor.tenantId(), conversationId));
        publish(actor.tenantId(), conversationId, "member.changed", null, view);
        return view;
    }

    public ConversationMembersView members(UUID conversationId) {
        requireIm();
        CallerIdentity actor = tenantActor();
        requireConversation(actor, conversationId, false);
        requireActiveMember(actor, conversationId);
        return new ConversationMembersView(store.listMembers(actor.tenantId(), conversationId));
    }

    @Transactional
    public MessageView sendMessage(UUID conversationId, SendMessageCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        requireConversation(actor, conversationId, true);
        requireActiveMember(actor, conversationId);
        NormalizedMessage normalized = normalizeMessage(actor, conversationId, command);
        MessageView message = store.insertMessage(UUID.randomUUID(), actor.tenantId(), conversationId, actor.userId(),
                normalized.clientMessageId(), normalized.messageType(), normalized.text(), normalized.attachmentId(),
                normalized.mentionedUserIds(), now());
        appendOutbox(actor.tenantId(), "MESSAGE", message.id(), "message.created",
                Map.of("conversationId", conversationId.toString(), "messageId", message.id().toString(),
                        "serverSeq", message.serverSeq(), "messageType", message.messageType()));
        publish(actor.tenantId(), conversationId, "message.created", message.serverSeq(), message);
        return message;
    }

    public MessagePageView messages(UUID conversationId, long afterSeq, int limit) {
        requireIm();
        CallerIdentity actor = tenantActor();
        requireConversation(actor, conversationId, false);
        requireActiveMember(actor, conversationId);
        int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? 30 : limit, MAX_HISTORY_LIMIT));
        List<MessageView> rows = store.listMessages(actor.tenantId(), conversationId, Math.max(0, afterSeq),
                normalizedLimit + 1);
        return new MessagePageView(rows.stream().limit(normalizedLimit).toList(), rows.size() > normalizedLimit);
    }

    @Transactional
    public MessageView recallMessage(UUID messageId, RecallMessageCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        MessageView existing = store.findMessage(actor.tenantId(), messageId)
                .orElseThrow(() -> notFound("消息不存在"));
        MemberRecord actorMember = requireActiveMember(actor, existing.conversationId());
        if (!existing.senderUserId().equals(actor.userId()) && !Set.of("OWNER", "ADMIN").contains(actorMember.role())) {
            throw forbidden("只有发送人、群主或管理员可以撤回消息");
        }
        MessageView recalled = store.recallMessage(actor.tenantId(), messageId, now());
        appendOutbox(actor.tenantId(), "MESSAGE", messageId, "message.recalled",
                Map.of("conversationId", recalled.conversationId().toString(), "messageId", messageId.toString()));
        publish(actor.tenantId(), recalled.conversationId(), "message.recalled", recalled.serverSeq(), recalled);
        return recalled;
    }

    @Transactional
    public void updateReadCursor(UUID conversationId, ReadCursorCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        requireConversation(actor, conversationId, false);
        requireActiveMember(actor, conversationId);
        if (command == null || command.serverSeq() < 0) throw badRequest("已读位置无效");
        store.updateReadCursor(actor.tenantId(), conversationId, actor.userId(), command.serverSeq(), now());
        publish(actor.tenantId(), conversationId, "read.updated", command.serverSeq(),
                Map.of("userId", actor.userId(), "serverSeq", command.serverSeq()));
    }

    @Transactional
    public AttachmentInitView initAttachment(AttachmentInitCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        if (command == null) throw badRequest("附件参数不能为空");
        String fileName = requireText(command.fileName(), "文件名不能为空", 180);
        if (command.sizeBytes() <= 0 || command.sizeBytes() > properties.maxAttachmentBytes()) {
            throw badRequest("附件大小无效");
        }
        String objectKey = actor.tenantId() + "/collaboration/" + actor.userId() + "/"
                + UUID.randomUUID() + "/" + sanitizeFileName(fileName);
        return store.createAttachment(UUID.randomUUID(), actor.tenantId(), actor.userId(), command, objectKey, now());
    }

    @Transactional
    public AttachmentView completeAttachment(AttachmentCompleteCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        if (command == null || command.attachmentId() == null) throw badRequest("附件ID不能为空");
        return store.completeAttachment(actor.tenantId(), actor.userId(), command, now());
    }

    @Transactional
    public MeetingView createMeeting(MeetingCreateCommand command) {
        requireMeeting();
        CallerIdentity actor = tenantActor();
        if (command == null || command.conversationId() == null) throw badRequest("会话ID不能为空");
        requireConversation(actor, command.conversationId(), false);
        requireActiveMember(actor, command.conversationId());
        UUID meetingId = UUID.randomUUID();
        String roomName = "tenant-" + actor.tenantId() + "-meeting-" + meetingId;
        MeetingView meeting = store.createMeeting(meetingId, actor.tenantId(), command.conversationId(), roomName,
                textOrDefault(command.title(), "语音会议", 120), actor.userId(), MEETING_MAX_PARTICIPANTS,
                SCREEN_SHARE_LIMIT, now());
        appendOutbox(actor.tenantId(), "MEETING", meetingId, "meeting.started",
                Map.of("conversationId", command.conversationId().toString(), "meetingId", meetingId.toString()));
        publish(actor.tenantId(), command.conversationId(), "meeting.started", null, meeting);
        return meeting;
    }

    public MeetingJoinTokenView joinMeeting(UUID meetingId, MeetingJoinTokenCommand command) {
        requireMeeting();
        CallerIdentity actor = tenantActor();
        MeetingView meeting = store.findMeeting(actor.tenantId(), meetingId)
                .orElseThrow(() -> notFound("会议不存在"));
        if (!"STARTED".equals(meeting.status())) throw conflict("会议已结束");
        requireActiveMember(actor, meeting.conversationId());
        String participantIdentity = actor.tenantId() + ":" + actor.userId();
        LiveKitTokenService.Token token = liveKitTokenService.issue(meeting.liveKitRoom(),
                participantIdentity, "用户-" + actor.userId().toString().substring(0, 8));
        return new MeetingJoinTokenView(meetingId, token.liveKitUrl(), meeting.liveKitRoom(),
                participantIdentity, token.token(), token.expiresAt());
    }

    @Transactional
    public DeviceView registerDevice(DeviceRegisterCommand command) {
        requireIm();
        CallerIdentity actor = tenantActor();
        if (command == null) throw badRequest("设备参数不能为空");
        requireText(command.deviceId(), "设备ID不能为空", 120);
        requireText(command.platform(), "平台不能为空", 24);
        requireText(command.pushProvider(), "推送提供方不能为空", 48);
        String pushToken = requireText(command.pushToken(), "推送Token不能为空", 512);
        return store.upsertDevice(UUID.randomUUID(), actor.tenantId(), actor.userId(), command,
                sha256(pushToken), now());
    }

    private NormalizedMessage normalizeMessage(CallerIdentity actor, UUID conversationId, SendMessageCommand command) {
        if (command == null) throw badRequest("消息参数不能为空");
        String clientMessageId = requireText(command.clientMessageId(), "clientMessageId不能为空", 120);
        String type = upper(command.messageType());
        if (!MESSAGE_TYPES.contains(type)) throw badRequest("消息类型无效");
        String text = trim(command.text());
        UUID attachmentId = command.attachmentId();
        if ("TEXT".equals(type)) {
            text = requireText(text, "文本消息不能为空", properties.maxMessageTextLength());
            attachmentId = null;
        } else {
            if (attachmentId == null) throw badRequest("媒体消息必须携带附件ID");
            AttachmentView attachment = store.findAttachment(actor.tenantId(), attachmentId)
                    .orElseThrow(() -> notFound("附件不存在"));
            if (!"COMPLETED".equals(attachment.status())) throw conflict("附件尚未完成上传");
            text = null;
        }
        List<UUID> mentioned = new ArrayList<>();
        for (UUID userId : command.mentionedUserIds()) {
            if (userId == null) continue;
            MemberRecord member = store.findMember(actor.tenantId(), conversationId, userId)
                    .orElseThrow(() -> badRequest("被@用户不是会话成员"));
            if (!member.active()) throw badRequest("被@用户不是会话成员");
            if (!mentioned.contains(userId)) mentioned.add(userId);
        }
        return new NormalizedMessage(clientMessageId, type, text, attachmentId, mentioned);
    }

    private ConversationRecord requireConversation(CallerIdentity actor, UUID conversationId, boolean lock) {
        if (conversationId == null) throw badRequest("会话ID不能为空");
        return store.requireConversation(actor.tenantId(), conversationId, lock);
    }

    private MemberRecord requireActiveMember(CallerIdentity actor, UUID conversationId) {
        MemberRecord member = store.findMember(actor.tenantId(), conversationId, actor.userId())
                .orElseThrow(() -> forbidden("不是会话成员"));
        if (!member.active()) throw forbidden("不是有效会话成员");
        return member;
    }

    private CallerIdentity tenantActor() {
        CallerIdentity actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())) throw forbidden("协作能力只支持租户用户身份");
        return actor;
    }

    private static void requireIm() {
        AuthorizationContext.requirePermission("collaboration:im:use");
    }

    private static void requireMeeting() {
        AuthorizationContext.requirePermission("collaboration:meeting:use");
    }

    private void ensureSelfProfile(CallerIdentity actor) {
        store.ensureDirectoryProfile(actor.tenantId(), actor.userId(),
                "用户-" + actor.userId().toString().substring(0, 8), now());
    }

    private void ensureProfile(UUID tenantId, UUID userId) {
        store.ensureDirectoryProfile(tenantId, userId, "用户-" + userId.toString().substring(0, 8), now());
    }

    private LinkedHashSet<UUID> normalizedMembers(List<UUID> requested, UUID actorUserId) {
        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        members.add(actorUserId);
        for (UUID userId : requested) {
            if (userId == null) throw badRequest("成员ID不能为空");
            members.add(userId);
        }
        return members;
    }

    private String directKey(Set<UUID> members) {
        return members.stream().map(UUID::toString).sorted().reduce((left, right) -> left + ":" + right)
                .orElseThrow(() -> badRequest("单聊成员不能为空"));
    }

    private void publish(UUID tenantId, UUID conversationId, String type, Long serverSeq, Object payload) {
        realtimeEvents.publishToConversation(tenantId, conversationId,
                new CollaborationEvent(UUID.randomUUID(), tenantId, type, conversationId, serverSeq, payload));
    }

    private void appendOutbox(UUID tenantId, String aggregateType, UUID aggregateId,
                              String eventType, Object payload) {
        try {
            store.appendOutboxEvent(UUID.randomUUID(), tenantId, aggregateType, aggregateId,
                    eventType, objectMapper.writeValueAsString(payload), now());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "协作事件写入失败", List.of());
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String textOrDefault(String value, String defaultValue, int maxLength) {
        String trimmed = trim(value);
        if (trimmed == null) return defaultValue;
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String requireText(String value, String message, int maxLength) {
        String trimmed = trim(value);
        if (trimmed == null) throw badRequest(message);
        if (trimmed.length() > maxLength) throw badRequest(message);
        return trimmed;
    }

    private static String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private record NormalizedMessage(
            String clientMessageId, String messageType, String text,
            UUID attachmentId, List<UUID> mentionedUserIds) {
        private NormalizedMessage {
            mentionedUserIds = List.copyOf(Objects.requireNonNull(mentionedUserIds));
        }
    }
}
