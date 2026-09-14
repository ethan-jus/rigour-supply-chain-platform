package com.rigour.collaboration.application.port.out;

import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentCompleteCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentInitCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentInitView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.AttachmentView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationMemberView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationSummaryView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DeviceRegisterCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DeviceView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.DirectoryUserView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MessageView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 协作服务本地事实存储端口；实现必须保证所有查询携带 tenantId。 */
public interface CollaborationStore {
    void ensureDirectoryProfile(UUID tenantId, UUID userId, String displayName, Instant now);

    List<DirectoryUserView> searchDirectory(UUID tenantId, String query, int limit);

    Optional<ConversationRecord> findDirectConversation(UUID tenantId, String directKey);

    ConversationRecord createConversation(UUID id, UUID tenantId, String type, String title,
                                          UUID ownerUserId, String directKey, Instant now);

    ConversationRecord requireConversation(UUID tenantId, UUID conversationId, boolean lock);

    List<ConversationSummaryView> listConversations(UUID tenantId, UUID userId);

    Optional<MemberRecord> findMember(UUID tenantId, UUID conversationId, UUID userId);

    List<ConversationMemberView> listMembers(UUID tenantId, UUID conversationId);

    void upsertMember(UUID tenantId, UUID conversationId, UUID userId, String role, Instant now);

    long activeMemberCount(UUID tenantId, UUID conversationId);

    Optional<AttachmentView> findAttachment(UUID tenantId, UUID attachmentId);

    AttachmentInitView createAttachment(UUID id, UUID tenantId, UUID ownerUserId,
                                        AttachmentInitCommand command, String objectKey, Instant now);

    AttachmentView completeAttachment(UUID tenantId, UUID ownerUserId, AttachmentCompleteCommand command, Instant now);

    MessageView insertMessage(UUID messageId, UUID tenantId, UUID conversationId, UUID senderUserId,
                              String clientMessageId, String messageType, String text, UUID attachmentId,
                              List<UUID> mentionedUserIds, Instant now);

    List<MessageView> listMessages(UUID tenantId, UUID conversationId, long afterSeq, int limit);

    Optional<MessageView> findMessage(UUID tenantId, UUID messageId);

    MessageView recallMessage(UUID tenantId, UUID messageId, Instant now);

    void updateReadCursor(UUID tenantId, UUID conversationId, UUID userId, long serverSeq, Instant now);

    MeetingView createMeeting(UUID meetingId, UUID tenantId, UUID conversationId, String liveKitRoom,
                              String title, UUID startedBy, int maxParticipants,
                              int screenShareLimit, Instant now);

    Optional<MeetingView> findMeeting(UUID tenantId, UUID meetingId);

    DeviceView upsertDevice(UUID id, UUID tenantId, UUID userId, DeviceRegisterCommand command,
                            String pushTokenHash, Instant now);

    void appendOutboxEvent(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId,
                           String eventType, String payloadJson, Instant now);

    record ConversationRecord(
            UUID id, UUID tenantId, String type, String title, UUID ownerUserId,
            String directKey, long version) {
    }

    record MemberRecord(UUID conversationId, UUID userId, String role, String status, long lastReadSeq) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
