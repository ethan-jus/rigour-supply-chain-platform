package com.rigour.collaboration.infrastructure.persistence;

import static com.rigour.collaboration.infrastructure.persistence.CollaborationUuidCodec.decode;
import static com.rigour.collaboration.infrastructure.persistence.CollaborationUuidCodec.encode;

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
import com.rigour.collaboration.api.v1.model.CollaborationModels.MessagePreviewView;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MessageView;
import com.rigour.collaboration.application.port.out.CollaborationStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC 版本协作事实存储；所有 SQL 都以 tenantId 为第一过滤条件。 */
@Repository
public class JdbcCollaborationStore implements CollaborationStore {
    private final JdbcTemplate jdbc;

    public JdbcCollaborationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureDirectoryProfile(UUID tenantId, UUID userId, String displayName, Instant now) {
        int updated = jdbc.update("""
                update collab_directory_profile
                   set display_name = coalesce(display_name, ?), status = 'ACTIVE', updated_at = ?
                 where tenant_id = ? and user_id = ?
                """, displayName, ts(now), encode(tenantId), encode(userId));
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into collab_directory_profile
                        (tenant_id, user_id, display_name, avatar_url, status, updated_at)
                        values (?, ?, ?, null, 'ACTIVE', ?)
                        """, encode(tenantId), encode(userId), displayName, ts(now));
            } catch (DataIntegrityViolationException ignored) {
                // 并发补投影时只需要确保存在，后续请求会读到已插入记录。
            }
        }
    }

    @Override
    public List<DirectoryUserView> searchDirectory(UUID tenantId, String query, int limit) {
        String like = query == null ? "%" : "%" + query + "%";
        return jdbc.query("""
                select user_id, display_name, avatar_url, status
                  from collab_directory_profile
                 where tenant_id = ? and status = 'ACTIVE' and display_name like ?
                 order by display_name asc
                 limit ?
                """, (rs, rowNum) -> new DirectoryUserView(
                decode(rs.getBytes("user_id")), rs.getString("display_name"),
                rs.getString("avatar_url"), rs.getString("status")
        ), encode(tenantId), like, limit);
    }

    @Override
    public Optional<ConversationRecord> findDirectConversation(UUID tenantId, String directKey) {
        List<ConversationRecord> rows = jdbc.query("""
                select id, tenant_id, type, title, owner_user_id, direct_key, version
                  from collab_conversation
                 where tenant_id = ? and direct_key = ?
                """, this::conversationRecord, encode(tenantId), directKey);
        return rows.stream().findFirst();
    }

    @Override
    public ConversationRecord createConversation(UUID id, UUID tenantId, String type, String title,
                                                 UUID ownerUserId, String directKey, Instant now) {
        jdbc.update("""
                insert into collab_conversation
                (id, tenant_id, type, title, owner_user_id, direct_key, created_by, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, encode(id), encode(tenantId), type, title, encode(ownerUserId), directKey,
                encode(ownerUserId), ts(now), ts(now));
        return requireConversation(tenantId, id, false);
    }

    @Override
    public ConversationRecord requireConversation(UUID tenantId, UUID conversationId, boolean lock) {
        String sql = """
                select id, tenant_id, type, title, owner_user_id, direct_key, version
                  from collab_conversation
                 where tenant_id = ? and id = ?
                """ + (lock ? " for update" : "");
        List<ConversationRecord> rows = jdbc.query(sql, this::conversationRecord, encode(tenantId),
                encode(conversationId));
        return rows.stream().findFirst().orElseThrow(() -> notFound("会话不存在"));
    }

    @Override
    public List<ConversationSummaryView> listConversations(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select c.id, c.type, c.title, c.owner_user_id, c.updated_at,
                       coalesce((select count(*) from collab_conversation_member cm
                                  where cm.tenant_id = c.tenant_id and cm.conversation_id = c.id
                                    and cm.member_status = 'ACTIVE'), 0) as member_count,
                       coalesce((select count(*) from collab_message unread
                                  where unread.tenant_id = c.tenant_id and unread.conversation_id = c.id
                                    and unread.server_seq > self.last_read_seq
                                    and unread.sender_user_id <> self.user_id
                                    and unread.status = 'ACTIVE'), 0) as unread_count,
                       m.id as message_id, m.sender_user_id, m.message_type, m.body_text,
                       m.server_seq, m.status as message_status, m.created_at as message_created_at
                  from collab_conversation c
                  join collab_conversation_member self
                    on self.tenant_id = c.tenant_id and self.conversation_id = c.id
                   and self.user_id = ? and self.member_status = 'ACTIVE'
                  left join collab_message m
                    on m.tenant_id = c.tenant_id and m.id = c.last_message_id
                 where c.tenant_id = ?
                 order by c.updated_at desc
                """, (rs, rowNum) -> {
            UUID messageId = decode(rs.getBytes("message_id"));
            MessagePreviewView preview = messageId == null ? null : new MessagePreviewView(
                    messageId, decode(rs.getBytes("sender_user_id")), rs.getString("message_type"),
                    previewText(rs.getString("message_type"), rs.getString("body_text"),
                            rs.getString("message_status")),
                    rs.getLong("server_seq"), rs.getString("message_status"),
                    instant(rs, "message_created_at"));
            return new ConversationSummaryView(decode(rs.getBytes("id")), rs.getString("type"),
                    title(rs.getString("type"), rs.getString("title")),
                    decode(rs.getBytes("owner_user_id")), rs.getInt("member_count"),
                    rs.getLong("unread_count"), preview, instant(rs, "updated_at"));
        }, encode(userId), encode(tenantId));
    }

    @Override
    public Optional<MemberRecord> findMember(UUID tenantId, UUID conversationId, UUID userId) {
        List<MemberRecord> rows = jdbc.query("""
                select conversation_id, user_id, role, member_status, last_read_seq
                  from collab_conversation_member
                 where tenant_id = ? and conversation_id = ? and user_id = ?
                """, this::memberRecord, encode(tenantId), encode(conversationId), encode(userId));
        return rows.stream().findFirst();
    }

    @Override
    public List<ConversationMemberView> listMembers(UUID tenantId, UUID conversationId) {
        return jdbc.query("""
                select cm.conversation_id, cm.user_id, coalesce(dp.display_name, '用户') as display_name,
                       cm.role, cm.member_status, cm.last_read_seq, cm.joined_at
                  from collab_conversation_member cm
                  left join collab_directory_profile dp
                    on dp.tenant_id = cm.tenant_id and dp.user_id = cm.user_id
                 where cm.tenant_id = ? and cm.conversation_id = ?
                 order by cm.joined_at asc
                """, (rs, rowNum) -> new ConversationMemberView(
                decode(rs.getBytes("conversation_id")), decode(rs.getBytes("user_id")),
                rs.getString("display_name"), rs.getString("role"), rs.getString("member_status"),
                rs.getLong("last_read_seq"), instant(rs, "joined_at")
        ), encode(tenantId), encode(conversationId));
    }

    @Override
    public void upsertMember(UUID tenantId, UUID conversationId, UUID userId, String role, Instant now) {
        int updated = jdbc.update("""
                update collab_conversation_member
                   set role = ?, member_status = 'ACTIVE', version = version + 1
                 where tenant_id = ? and conversation_id = ? and user_id = ?
                """, role, encode(tenantId), encode(conversationId), encode(userId));
        if (updated == 0) {
            jdbc.update("""
                    insert into collab_conversation_member
                    (tenant_id, conversation_id, user_id, role, member_status, joined_at, last_read_seq, muted, version)
                    values (?, ?, ?, ?, 'ACTIVE', ?, 0, false, 0)
                    """, encode(tenantId), encode(conversationId), encode(userId), role, ts(now));
        }
        jdbc.update("""
                update collab_conversation
                   set updated_at = ?, version = version + 1
                 where tenant_id = ? and id = ?
                """, ts(now), encode(tenantId), encode(conversationId));
    }

    @Override
    public long activeMemberCount(UUID tenantId, UUID conversationId) {
        Long value = jdbc.queryForObject("""
                select count(*) from collab_conversation_member
                 where tenant_id = ? and conversation_id = ? and member_status = 'ACTIVE'
                """, Long.class, encode(tenantId), encode(conversationId));
        return value == null ? 0 : value;
    }

    @Override
    public Optional<AttachmentView> findAttachment(UUID tenantId, UUID attachmentId) {
        List<AttachmentView> rows = jdbc.query("""
                select id, object_key, file_name, content_type, size_bytes, status, created_at, completed_at
                  from collab_attachment
                 where tenant_id = ? and id = ?
                """, this::attachmentView, encode(tenantId), encode(attachmentId));
        return rows.stream().findFirst();
    }

    @Override
    public AttachmentInitView createAttachment(UUID id, UUID tenantId, UUID ownerUserId,
                                               AttachmentInitCommand command, String objectKey, Instant now) {
        jdbc.update("""
                insert into collab_attachment
                (id, tenant_id, owner_user_id, object_key, file_name, content_type, size_bytes, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, encode(id), encode(tenantId), encode(ownerUserId), objectKey, command.fileName(),
                command.contentType(), command.sizeBytes(), ts(now));
        return new AttachmentInitView(id, objectKey, null);
    }

    @Override
    public AttachmentView completeAttachment(UUID tenantId, UUID ownerUserId,
                                             AttachmentCompleteCommand command, Instant now) {
        int updated = jdbc.update("""
                update collab_attachment
                   set object_key = ?, file_name = coalesce(?, file_name), content_type = coalesce(?, content_type),
                       size_bytes = ?, status = 'COMPLETED', completed_at = ?
                 where tenant_id = ? and owner_user_id = ? and id = ? and status = 'PENDING'
                """, command.objectKey(), command.fileName(), command.contentType(), command.sizeBytes(), ts(now),
                encode(tenantId), encode(ownerUserId), encode(command.attachmentId()));
        if (updated == 0) throw conflict("附件不存在或状态不允许完成");
        return findAttachment(tenantId, command.attachmentId()).orElseThrow(() -> notFound("附件不存在"));
    }

    @Override
    public MessageView insertMessage(UUID messageId, UUID tenantId, UUID conversationId, UUID senderUserId,
                                     String clientMessageId, String messageType, String text, UUID attachmentId,
                                     List<UUID> mentionedUserIds, Instant now) {
        Long next = jdbc.queryForObject("""
                select coalesce(max(server_seq), 0) + 1
                  from collab_message
                 where tenant_id = ? and conversation_id = ?
                """, Long.class, encode(tenantId), encode(conversationId));
        long serverSeq = next == null ? 1 : next;
        try {
            jdbc.update("""
                    insert into collab_message
                    (id, tenant_id, conversation_id, sender_user_id, client_message_id, message_type,
                     body_text, attachment_id, mentioned_user_ids, server_seq, status, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, encode(messageId), encode(tenantId), encode(conversationId), encode(senderUserId),
                    clientMessageId, messageType, text, encode(attachmentId), joinUuids(mentionedUserIds),
                    serverSeq, ts(now));
        } catch (DataIntegrityViolationException ex) {
            return findByClientMessageId(tenantId, conversationId, senderUserId, clientMessageId)
                    .orElseThrow(() -> ex);
        }
        jdbc.update("""
                update collab_conversation
                   set last_message_id = ?, last_message_preview = ?, last_message_at = ?,
                       updated_at = ?, version = version + 1
                 where tenant_id = ? and id = ?
                """, encode(messageId), previewText(messageType, text, "ACTIVE"), ts(now), ts(now),
                encode(tenantId), encode(conversationId));
        return findMessage(tenantId, messageId).orElseThrow(() -> notFound("消息不存在"));
    }

    @Override
    public List<MessageView> listMessages(UUID tenantId, UUID conversationId, long afterSeq, int limit) {
        return jdbc.query("""
                select m.id, m.conversation_id, m.sender_user_id, m.client_message_id, m.message_type,
                       m.body_text, m.mentioned_user_ids, m.server_seq, m.status, m.recalled_at, m.created_at,
                       a.id as attachment_id, a.object_key, a.file_name, a.content_type, a.size_bytes,
                       a.status as attachment_status, a.created_at as attachment_created_at,
                       a.completed_at as attachment_completed_at
                  from collab_message m
                  left join collab_attachment a on a.tenant_id = m.tenant_id and a.id = m.attachment_id
                 where m.tenant_id = ? and m.conversation_id = ? and m.server_seq > ?
                 order by m.server_seq asc
                 limit ?
                """, this::messageView, encode(tenantId), encode(conversationId), afterSeq, limit);
    }

    @Override
    public Optional<MessageView> findMessage(UUID tenantId, UUID messageId) {
        List<MessageView> rows = jdbc.query("""
                select m.id, m.conversation_id, m.sender_user_id, m.client_message_id, m.message_type,
                       m.body_text, m.mentioned_user_ids, m.server_seq, m.status, m.recalled_at, m.created_at,
                       a.id as attachment_id, a.object_key, a.file_name, a.content_type, a.size_bytes,
                       a.status as attachment_status, a.created_at as attachment_created_at,
                       a.completed_at as attachment_completed_at
                  from collab_message m
                  left join collab_attachment a on a.tenant_id = m.tenant_id and a.id = m.attachment_id
                 where m.tenant_id = ? and m.id = ?
                """, this::messageView, encode(tenantId), encode(messageId));
        return rows.stream().findFirst();
    }

    @Override
    public MessageView recallMessage(UUID tenantId, UUID messageId, Instant now) {
        int updated = jdbc.update("""
                update collab_message
                   set status = 'RECALLED', body_text = null, attachment_id = null, recalled_at = ?
                 where tenant_id = ? and id = ? and status = 'ACTIVE'
                """, ts(now), encode(tenantId), encode(messageId));
        if (updated == 0) throw conflict("消息不存在或已撤回");
        return findMessage(tenantId, messageId).orElseThrow(() -> notFound("消息不存在"));
    }

    @Override
    public void updateReadCursor(UUID tenantId, UUID conversationId, UUID userId, long serverSeq, Instant now) {
        jdbc.update("""
                update collab_conversation_member
                   set last_read_seq = case when last_read_seq < ? then ? else last_read_seq end
                 where tenant_id = ? and conversation_id = ? and user_id = ? and member_status = 'ACTIVE'
                """, serverSeq, serverSeq, encode(tenantId), encode(conversationId), encode(userId));
    }

    @Override
    public MeetingView createMeeting(UUID meetingId, UUID tenantId, UUID conversationId, String liveKitRoom,
                                     String title, UUID startedBy, int maxParticipants,
                                     int screenShareLimit, Instant now) {
        jdbc.update("""
                insert into collab_meeting
                (id, tenant_id, conversation_id, livekit_room, title, status, started_by,
                 started_at, max_participants, screen_share_limit)
                values (?, ?, ?, ?, ?, 'STARTED', ?, ?, ?, ?)
                """, encode(meetingId), encode(tenantId), encode(conversationId), liveKitRoom, title,
                encode(startedBy), ts(now), maxParticipants, screenShareLimit);
        return findMeeting(tenantId, meetingId).orElseThrow(() -> notFound("会议不存在"));
    }

    @Override
    public Optional<MeetingView> findMeeting(UUID tenantId, UUID meetingId) {
        List<MeetingView> rows = jdbc.query("""
                select id, conversation_id, livekit_room, title, status, started_by, started_at,
                       ended_at, max_participants, screen_share_limit
                  from collab_meeting
                 where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new MeetingView(
                decode(rs.getBytes("id")), decode(rs.getBytes("conversation_id")),
                rs.getString("livekit_room"), rs.getString("title"), rs.getString("status"),
                decode(rs.getBytes("started_by")), instant(rs, "started_at"), instant(rs, "ended_at"),
                rs.getInt("max_participants"), rs.getInt("screen_share_limit")
        ), encode(tenantId), encode(meetingId));
        return rows.stream().findFirst();
    }

    @Override
    public DeviceView upsertDevice(UUID id, UUID tenantId, UUID userId, DeviceRegisterCommand command,
                                   String pushTokenHash, Instant now) {
        int updated = jdbc.update("""
                update collab_device
                   set push_provider = ?, push_token = ?, push_token_hash = ?, status = 'ACTIVE', last_seen_at = ?
                 where tenant_id = ? and user_id = ? and platform = ? and device_id = ?
                """, command.pushProvider(), command.pushToken(), pushTokenHash, ts(now), encode(tenantId),
                encode(userId), command.platform(), command.deviceId());
        UUID deviceId = id;
        if (updated == 0) {
            jdbc.update("""
                    insert into collab_device
                    (id, tenant_id, user_id, device_id, platform, push_provider, push_token,
                     push_token_hash, status, created_at, last_seen_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """, encode(id), encode(tenantId), encode(userId), command.deviceId(), command.platform(),
                    command.pushProvider(), command.pushToken(), pushTokenHash, ts(now), ts(now));
        } else {
            deviceId = jdbc.queryForObject("""
                    select id from collab_device
                     where tenant_id = ? and user_id = ? and platform = ? and device_id = ?
                    """, (rs, rowNum) -> decode(rs.getBytes("id")), encode(tenantId), encode(userId),
                    command.platform(), command.deviceId());
        }
        UUID finalDeviceId = deviceId;
        return jdbc.queryForObject("""
                select id, platform, push_provider, status, last_seen_at
                  from collab_device
                 where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new DeviceView(finalDeviceId, rs.getString("platform"),
                rs.getString("push_provider"), rs.getString("status"), instant(rs, "last_seen_at")),
                encode(tenantId), encode(finalDeviceId));
    }

    @Override
    public void appendOutboxEvent(UUID eventId, UUID tenantId, String aggregateType, UUID aggregateId,
                                  String eventType, String payloadJson, Instant now) {
        jdbc.update("""
                insert into collab_outbox_event
                (id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json, status, created_at)
                values (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, encode(eventId), encode(tenantId), aggregateType, encode(aggregateId), eventType,
                payloadJson, ts(now));
    }

    private Optional<MessageView> findByClientMessageId(UUID tenantId, UUID conversationId, UUID senderUserId,
                                                        String clientMessageId) {
        List<MessageView> rows = jdbc.query("""
                select m.id, m.conversation_id, m.sender_user_id, m.client_message_id, m.message_type,
                       m.body_text, m.mentioned_user_ids, m.server_seq, m.status, m.recalled_at, m.created_at,
                       a.id as attachment_id, a.object_key, a.file_name, a.content_type, a.size_bytes,
                       a.status as attachment_status, a.created_at as attachment_created_at,
                       a.completed_at as attachment_completed_at
                  from collab_message m
                  left join collab_attachment a on a.tenant_id = m.tenant_id and a.id = m.attachment_id
                 where m.tenant_id = ? and m.conversation_id = ? and m.sender_user_id = ?
                   and m.client_message_id = ?
                """, this::messageView, encode(tenantId), encode(conversationId), encode(senderUserId),
                clientMessageId);
        return rows.stream().findFirst();
    }

    private ConversationRecord conversationRecord(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ConversationRecord(decode(rs.getBytes("id")), decode(rs.getBytes("tenant_id")),
                rs.getString("type"), rs.getString("title"), decode(rs.getBytes("owner_user_id")),
                rs.getString("direct_key"), rs.getLong("version"));
    }

    private MemberRecord memberRecord(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MemberRecord(decode(rs.getBytes("conversation_id")), decode(rs.getBytes("user_id")),
                rs.getString("role"), rs.getString("member_status"), rs.getLong("last_read_seq"));
    }

    private MessageView messageView(ResultSet rs, int rowNum) throws java.sql.SQLException {
        AttachmentView attachment = decode(rs.getBytes("attachment_id")) == null ? null : new AttachmentView(
                decode(rs.getBytes("attachment_id")), rs.getString("object_key"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("attachment_status"),
                instant(rs, "attachment_created_at"), instant(rs, "attachment_completed_at"));
        String status = rs.getString("status");
        return new MessageView(decode(rs.getBytes("id")), decode(rs.getBytes("conversation_id")),
                decode(rs.getBytes("sender_user_id")), rs.getString("client_message_id"),
                rs.getString("message_type"), "RECALLED".equals(status) ? null : rs.getString("body_text"),
                attachment, splitUuids(rs.getString("mentioned_user_ids")), rs.getLong("server_seq"),
                status, instant(rs, "recalled_at"), instant(rs, "created_at"));
    }

    private AttachmentView attachmentView(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AttachmentView(decode(rs.getBytes("id")), rs.getString("object_key"),
                rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    private static String joinUuids(List<UUID> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(",", values.stream().map(UUID::toString).toList());
    }

    private static List<UUID> splitUuids(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank())
                .map(UUID::fromString)
                .toList();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String title(String type, String title) {
        if (title != null && !title.isBlank()) return title;
        return "DIRECT".equals(type) ? "单聊" : "群聊";
    }

    private static String previewText(String messageType, String text, String status) {
        if ("RECALLED".equals(status)) return "消息已撤回";
        if ("TEXT".equals(messageType)) return text;
        if ("IMAGE".equals(messageType)) return "[图片]";
        if ("VOICE".equals(messageType)) return "[语音]";
        if ("FILE".equals(messageType)) return "[文件]";
        return "[消息]";
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }
}
