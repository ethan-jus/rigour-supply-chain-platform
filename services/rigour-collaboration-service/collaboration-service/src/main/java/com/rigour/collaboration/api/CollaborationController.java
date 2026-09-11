package com.rigour.collaboration.api;

import com.rigour.collaboration.api.v1.CollaborationApi;
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
import com.rigour.collaboration.application.service.CollaborationService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 内部 IM、附件、会议和设备注册 HTTP 入口。 */
@RestController
public final class CollaborationController {
    private final CollaborationService service;

    public CollaborationController(CollaborationService service) {
        this.service = service;
    }

    @GetMapping(CollaborationApi.DIRECTORY_PATH)
    public ApiResponse<DirectoryView> directory(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(service.directory(query, limit));
    }

    @PostMapping(CollaborationApi.CONVERSATIONS_PATH)
    public ApiResponse<ConversationSummaryView> createConversation(@RequestBody ConversationCreateCommand command) {
        return ApiResponse.success(service.createConversation(command));
    }

    @GetMapping(CollaborationApi.CONVERSATIONS_PATH)
    public ApiResponse<ConversationListView> conversations() {
        return ApiResponse.success(service.conversations());
    }

    @PostMapping(CollaborationApi.MEMBERS_PATH)
    public ApiResponse<ConversationMembersView> addMembers(
            @PathVariable("conversationId") UUID conversationId,
            @RequestBody ConversationMemberCommand command) {
        return ApiResponse.success(service.addMembers(conversationId, command));
    }

    @GetMapping(CollaborationApi.MEMBERS_PATH)
    public ApiResponse<ConversationMembersView> members(@PathVariable("conversationId") UUID conversationId) {
        return ApiResponse.success(service.members(conversationId));
    }

    @PostMapping(CollaborationApi.MESSAGES_PATH)
    public ApiResponse<MessageView> sendMessage(
            @PathVariable("conversationId") UUID conversationId,
            @RequestBody SendMessageCommand command) {
        return ApiResponse.success(service.sendMessage(conversationId, command));
    }

    @GetMapping(CollaborationApi.MESSAGES_PATH)
    public ApiResponse<MessagePageView> messages(
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        return ApiResponse.success(service.messages(conversationId, afterSeq, limit));
    }

    @PostMapping(CollaborationApi.RECALL_MESSAGE_PATH)
    public ApiResponse<MessageView> recallMessage(
            @PathVariable("messageId") UUID messageId,
            @RequestBody(required = false) RecallMessageCommand command) {
        return ApiResponse.success(service.recallMessage(messageId, command));
    }

    @PostMapping(CollaborationApi.READ_CURSOR_PATH)
    public ApiResponse<Void> updateReadCursor(
            @PathVariable("conversationId") UUID conversationId,
            @RequestBody ReadCursorCommand command) {
        service.updateReadCursor(conversationId, command);
        return ApiResponse.success(null);
    }

    @PostMapping(CollaborationApi.ATTACHMENT_INIT_PATH)
    public ApiResponse<AttachmentInitView> initAttachment(@RequestBody AttachmentInitCommand command) {
        return ApiResponse.success(service.initAttachment(command));
    }

    @PostMapping(CollaborationApi.ATTACHMENT_COMPLETE_PATH)
    public ApiResponse<AttachmentView> completeAttachment(@RequestBody AttachmentCompleteCommand command) {
        return ApiResponse.success(service.completeAttachment(command));
    }

    @PostMapping(CollaborationApi.MEETINGS_PATH)
    public ApiResponse<MeetingView> createMeeting(@RequestBody MeetingCreateCommand command) {
        return ApiResponse.success(service.createMeeting(command));
    }

    @PostMapping(CollaborationApi.MEETING_JOIN_TOKEN_PATH)
    public ApiResponse<MeetingJoinTokenView> joinMeeting(
            @PathVariable("meetingId") UUID meetingId,
            @RequestBody(required = false) MeetingJoinTokenCommand command) {
        return ApiResponse.success(service.joinMeeting(meetingId, command));
    }

    @PostMapping(CollaborationApi.DEVICES_PATH)
    public ApiResponse<DeviceView> registerDevice(@RequestBody DeviceRegisterCommand command) {
        return ApiResponse.success(service.registerDevice(command));
    }
}
