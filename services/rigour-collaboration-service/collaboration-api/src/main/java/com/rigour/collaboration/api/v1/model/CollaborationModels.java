package com.rigour.collaboration.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 内部协作 V1 模型；消息正文和媒体授权均由所属租户边界保护。 */
public final class CollaborationModels {
    private CollaborationModels() {
    }

    public record DirectoryUserView(UUID userId, String displayName, String avatarUrl, String status) {
    }

    public record DirectoryView(List<DirectoryUserView> users) {
        public DirectoryView {
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    public record ConversationCreateCommand(String type, String title, List<UUID> memberIds) {
        public ConversationCreateCommand {
            memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
        }
    }

    public record ConversationSummaryView(
            UUID id, String type, String title, UUID ownerUserId, int memberCount, long unreadCount,
            MessagePreviewView lastMessage, Instant updatedAt) {
    }

    public record ConversationListView(List<ConversationSummaryView> items) {
        public ConversationListView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record MessagePreviewView(
            UUID messageId, UUID senderUserId, String messageType, String previewText,
            long serverSeq, String status, Instant createdAt) {
    }

    public record ConversationMemberCommand(List<UUID> userIds, String role) {
        public ConversationMemberCommand {
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
        }
    }

    public record ConversationMemberView(
            UUID conversationId, UUID userId, String displayName, String role,
            String status, long lastReadSeq, Instant joinedAt) {
    }

    public record ConversationMembersView(List<ConversationMemberView> items) {
        public ConversationMembersView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SendMessageCommand(
            String clientMessageId, String messageType, String text,
            UUID attachmentId, List<UUID> mentionedUserIds) {
        public SendMessageCommand {
            mentionedUserIds = mentionedUserIds == null ? List.of() : List.copyOf(mentionedUserIds);
        }
    }

    public record AttachmentView(
            UUID id, String objectKey, String fileName, String contentType,
            long sizeBytes, String status, Instant createdAt, Instant completedAt) {
    }

    public record MessageView(
            UUID id, UUID conversationId, UUID senderUserId, String clientMessageId,
            String messageType, String text, AttachmentView attachment,
            List<UUID> mentionedUserIds, long serverSeq, String status,
            Instant recalledAt, Instant createdAt) {
        public MessageView {
            mentionedUserIds = mentionedUserIds == null ? List.of() : List.copyOf(mentionedUserIds);
        }
    }

    public record MessagePageView(List<MessageView> items, boolean hasMore) {
        public MessagePageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record RecallMessageCommand(String reason) {
    }

    public record ReadCursorCommand(long serverSeq) {
    }

    public record AttachmentInitCommand(String fileName, String contentType, long sizeBytes) {
    }

    public record AttachmentInitView(UUID attachmentId, String objectKey, String uploadUrl) {
    }

    public record AttachmentCompleteCommand(
            UUID attachmentId, String objectKey, String fileName, String contentType, long sizeBytes) {
    }

    public record DeviceRegisterCommand(String deviceId, String platform, String pushProvider, String pushToken) {
    }

    public record DeviceView(UUID id, String platform, String pushProvider, String status, Instant lastSeenAt) {
    }

    public record MeetingCreateCommand(UUID conversationId, String title) {
    }

    public record MeetingView(
            UUID id, UUID conversationId, String liveKitRoom, String title, String status,
            UUID startedBy, Instant startedAt, Instant endedAt,
            int maxParticipants, int screenShareLimit) {
    }

    public record MeetingJoinTokenCommand(String deviceId) {
    }

    public record MeetingJoinTokenView(
            UUID meetingId, String liveKitUrl, String roomName,
            String participantIdentity, String token, Instant expiresAt) {
    }
}
