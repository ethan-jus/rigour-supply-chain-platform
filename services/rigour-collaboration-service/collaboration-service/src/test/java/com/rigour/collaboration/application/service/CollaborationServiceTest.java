package com.rigour.collaboration.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.collaboration.api.v1.model.CollaborationModels.ConversationCreateCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.MeetingCreateCommand;
import com.rigour.collaboration.api.v1.model.CollaborationModels.SendMessageCommand;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.TestAuthorizationContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CollaborationServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired
    private CollaborationService service;

    @AfterEach
    void tearDown() {
        TestAuthorizationContext.clear();
    }

    @Test
    void directMessageIsIdempotentAndRecallHidesBody() {
        TestAuthorizationContext.tenant(TENANT_ID, USER_ID);

        var conversation = service.createConversation(new ConversationCreateCommand("DIRECT", null, List.of(OTHER_ID)));
        var sent = service.sendMessage(conversation.id(),
                new SendMessageCommand("client-1", "TEXT", "hello", null, List.of(OTHER_ID)));
        var duplicate = service.sendMessage(conversation.id(),
                new SendMessageCommand("client-1", "TEXT", "hello", null, List.of(OTHER_ID)));

        assertThat(duplicate.id()).isEqualTo(sent.id());
        assertThat(service.messages(conversation.id(), 0, 10).items()).hasSize(1);
        assertThat(service.conversations().items().getFirst().unreadCount()).isZero();

        var recalled = service.recallMessage(sent.id(), null);
        assertThat(recalled.status()).isEqualTo("RECALLED");
        assertThat(recalled.text()).isNull();
    }

    @Test
    void meetingJoinTokenIsSignedForConversationMember() {
        TestAuthorizationContext.tenant(TENANT_ID, USER_ID);

        var conversation = service.createConversation(new ConversationCreateCommand("GROUP", "销售群", List.of(OTHER_ID)));
        var meeting = service.createMeeting(new MeetingCreateCommand(conversation.id(), "早会"));
        var token = service.joinMeeting(meeting.id(), null);

        assertThat(token.liveKitUrl()).isEqualTo("wss://livekit.test");
        assertThat(token.roomName()).isEqualTo(meeting.liveKitRoom());
        assertThat(token.token()).contains(".");
    }

    @Test
    void collaborationApiRejectsTenantUserWithoutImPermission() {
        TestAuthorizationContext.tenant(TENANT_ID, USER_ID, Set.of("sales:context:read"));

        assertThatThrownBy(() -> service.conversations())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessageContaining("collaboration:im:use");
    }
}
