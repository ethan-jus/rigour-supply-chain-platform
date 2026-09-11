package com.rigour.collaboration.api.v1;

/** 内部协作 V1 HTTP 路径常量。 */
public interface CollaborationApi {
    String BASE_PATH = "/api/v1";
    String DIRECTORY_PATH = BASE_PATH + "/collaboration/directory";
    String CONVERSATIONS_PATH = BASE_PATH + "/conversations";
    String CONVERSATION_PATH = CONVERSATIONS_PATH + "/{conversationId}";
    String MEMBERS_PATH = CONVERSATION_PATH + "/members";
    String MESSAGES_PATH = CONVERSATION_PATH + "/messages";
    String READ_CURSOR_PATH = CONVERSATION_PATH + "/read-cursor";
    String RECALL_MESSAGE_PATH = BASE_PATH + "/messages/{messageId}/recall";
    String ATTACHMENT_INIT_PATH = BASE_PATH + "/attachments/init";
    String ATTACHMENT_COMPLETE_PATH = BASE_PATH + "/attachments/complete";
    String MEETINGS_PATH = BASE_PATH + "/meetings";
    String MEETING_JOIN_TOKEN_PATH = MEETINGS_PATH + "/{meetingId}/join-token";
    String DEVICES_PATH = BASE_PATH + "/devices";
}
