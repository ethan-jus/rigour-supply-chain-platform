create table collab_directory_profile (
    tenant_id binary(16) not null,
    user_id binary(16) not null,
    display_name varchar(120) not null,
    avatar_url varchar(512),
    status varchar(32) not null,
    updated_at timestamp(3) not null,
    primary key (tenant_id, user_id),
    index idx_collab_directory_display (tenant_id, status, display_name)
);

create table collab_conversation (
    id binary(16) not null,
    tenant_id binary(16) not null,
    type varchar(32) not null,
    title varchar(120),
    owner_user_id binary(16) not null,
    direct_key varchar(80),
    last_message_id binary(16),
    last_message_preview varchar(240),
    last_message_at timestamp(3),
    created_by binary(16) not null,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    version bigint not null default 0,
    primary key (id),
    unique key uk_collab_conversation_tenant_id (tenant_id, id),
    unique key uk_collab_conversation_direct (tenant_id, direct_key),
    index idx_collab_conversation_tenant_updated (tenant_id, updated_at)
);

create table collab_conversation_member (
    tenant_id binary(16) not null,
    conversation_id binary(16) not null,
    user_id binary(16) not null,
    role varchar(32) not null,
    member_status varchar(32) not null,
    joined_at timestamp(3) not null,
    last_read_seq bigint not null default 0,
    muted boolean not null default false,
    version bigint not null default 0,
    primary key (tenant_id, conversation_id, user_id),
    index idx_collab_member_user (tenant_id, user_id, member_status),
    index idx_collab_member_conversation (tenant_id, conversation_id, member_status)
);

create table collab_attachment (
    id binary(16) not null,
    tenant_id binary(16) not null,
    owner_user_id binary(16) not null,
    object_key varchar(512) not null,
    file_name varchar(180) not null,
    content_type varchar(120),
    size_bytes bigint not null,
    status varchar(32) not null,
    created_at timestamp(3) not null,
    completed_at timestamp(3),
    primary key (id),
    unique key uk_collab_attachment_tenant_id (tenant_id, id),
    index idx_collab_attachment_owner (tenant_id, owner_user_id, status)
);

create table collab_message (
    id binary(16) not null,
    tenant_id binary(16) not null,
    conversation_id binary(16) not null,
    sender_user_id binary(16) not null,
    client_message_id varchar(120) not null,
    message_type varchar(32) not null,
    body_text text,
    attachment_id binary(16),
    mentioned_user_ids text,
    server_seq bigint not null,
    status varchar(32) not null,
    recalled_at timestamp(3),
    created_at timestamp(3) not null,
    primary key (id),
    unique key uk_collab_message_tenant_id (tenant_id, id),
    unique key uk_collab_message_client (tenant_id, conversation_id, sender_user_id, client_message_id),
    unique key uk_collab_message_seq (tenant_id, conversation_id, server_seq),
    index idx_collab_message_conversation (tenant_id, conversation_id, server_seq),
    index idx_collab_message_sender (tenant_id, sender_user_id, created_at)
);

create table collab_meeting (
    id binary(16) not null,
    tenant_id binary(16) not null,
    conversation_id binary(16) not null,
    livekit_room varchar(180) not null,
    title varchar(120) not null,
    status varchar(32) not null,
    started_by binary(16) not null,
    started_at timestamp(3) not null,
    ended_at timestamp(3),
    max_participants int not null,
    screen_share_limit int not null,
    primary key (id),
    unique key uk_collab_meeting_tenant_id (tenant_id, id),
    unique key uk_collab_meeting_room (tenant_id, livekit_room),
    index idx_collab_meeting_conversation (tenant_id, conversation_id, status, started_at)
);

create table collab_device (
    id binary(16) not null,
    tenant_id binary(16) not null,
    user_id binary(16) not null,
    device_id varchar(120) not null,
    platform varchar(24) not null,
    push_provider varchar(48) not null,
    push_token varchar(512) not null,
    push_token_hash varchar(64) not null,
    status varchar(32) not null,
    created_at timestamp(3) not null,
    last_seen_at timestamp(3) not null,
    primary key (id),
    unique key uk_collab_device_identity (tenant_id, user_id, platform, device_id),
    index idx_collab_device_user (tenant_id, user_id, status)
);

create table collab_outbox_event (
    id binary(16) not null,
    tenant_id binary(16) not null,
    aggregate_type varchar(64) not null,
    aggregate_id binary(16) not null,
    event_type varchar(80) not null,
    payload_json text not null,
    status varchar(32) not null,
    created_at timestamp(3) not null,
    published_at timestamp(3),
    primary key (id),
    index idx_collab_outbox_dispatch (status, created_at),
    index idx_collab_outbox_aggregate (tenant_id, aggregate_type, aggregate_id)
);
