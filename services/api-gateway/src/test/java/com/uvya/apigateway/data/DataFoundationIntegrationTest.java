package com.uvya.apigateway.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uvya.apigateway.auth.domain.AuthAuditLogEntity;
import com.uvya.apigateway.auth.domain.AuthSessionEntity;
import com.uvya.apigateway.auth.domain.DeviceEntity;
import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.repository.AuthAuditLogRepository;
import com.uvya.apigateway.auth.repository.AuthSessionRepository;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.service.AuthRateLimiter;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.domain.ChatType;
import com.uvya.apigateway.data.domain.MessageEntity;
import com.uvya.apigateway.data.domain.MessageStatus;
import com.uvya.apigateway.data.domain.OutboxEventEntity;
import com.uvya.apigateway.data.repository.BlockedUserRepository;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.IdempotencyKeyRepository;
import com.uvya.apigateway.data.repository.MessageReactionRepository;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.data.repository.MessageVersionRepository;
import com.uvya.apigateway.data.repository.OutboxEventRepository;
import com.uvya.apigateway.data.repository.ReadStateRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;
import com.uvya.apigateway.data.service.BlockedUserService;
import com.uvya.apigateway.data.service.ChatService;
import com.uvya.apigateway.data.service.MessageCreationCommand;
import com.uvya.apigateway.data.service.MessageService;
import com.uvya.apigateway.data.service.MessageVersionService;
import com.uvya.apigateway.data.service.OutboxPublicationService;
import com.uvya.apigateway.data.service.ReadStateService;
import com.uvya.apigateway.data.service.ReactionService;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class DataFoundationIntegrationTest {
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AuthSessionRepository sessionRepository;
    @Autowired private AuthAuditLogRepository auditLogRepository;
    @Autowired private ChatService chatService;
    @Autowired private ChatMemberRepository memberRepository;
    @Autowired private MessageService messageService;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageVersionService versionService;
    @Autowired private MessageVersionRepository versionRepository;
    @Autowired private ReactionService reactionService;
    @Autowired private MessageReactionRepository reactionRepository;
    @Autowired private ReadStateService readStateService;
    @Autowired private ReadStateRepository readStateRepository;
    @Autowired private BlockedUserService blockedUserService;
    @Autowired private BlockedUserRepository blockedUserRepository;
    @Autowired private IdempotencyKeyRepository idempotencyRepository;
    @Autowired private UserInboxRepository inboxRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private OutboxPublicationService publicationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void phaseTwoUsersDevicesSessionsAndAuditTablesRemainCompatible() {
        UserEntity user = user();
        DeviceEntity device = device(user.getId());
        Instant now = Instant.now();
        AuthSessionEntity session = new AuthSessionEntity(UUID.randomUUID(), user.getId(), device.getId(),
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                now.plusSeconds(3600), now);
        sessionRepository.save(session);
        auditLogRepository.save(new AuthAuditLogEntity("DATA_FOUNDATION_TEST", user.getId(), device.getId(),
                session.getId(), "127.0.0.1", "trace-test", "{}", now));

        assertThat(sessionRepository.findById(session.getId())).isPresent();
        assertThat(auditLogRepository.countByEventType("DATA_FOUNDATION_TEST")).isEqualTo(1);
    }

    @Test
    void schemaContainsEveryPhaseOneTableAndRequiredMessageColumns() {
        List<String> tables = List.of("APP_USERS", "DEVICES", "AUTH_SESSIONS", "AUTH_AUDIT_LOGS", "CHATS",
                "CHAT_MEMBERS", "MESSAGES", "MESSAGE_REACTIONS", "MESSAGE_VERSIONS", "USER_INBOX",
                "READ_STATES", "BLOCKED_USERS", "IDEMPOTENCY_KEYS", "OUTBOX_EVENTS");
        for (String table : tables) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables where table_name = ?", Integer.class, table);
            assertThat(count).as("table %s", table).isEqualTo(1);
        }
        List<String> requiredColumns = List.of("MESSAGE_ID", "CHAT_ID", "SENDER_ID", "SENDER_DEVICE_ID",
                "CLIENT_MESSAGE_ID", "SEQUENCE", "MESSAGE_TYPE", "BODY", "REPLY_TO_MESSAGE_ID",
                "FORWARDED_FROM_MESSAGE_ID", "CREATED_AT", "EDITED_AT", "DELETED_AT", "VERSION", "STATUS");
        for (String column : requiredColumns) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns where table_name = 'MESSAGES'"
                            + " and column_name = ?", Integer.class, column);
            assertThat(count).as("messages.%s", column).isEqualTo(1);
        }
    }

    @Test
    void chatMembershipMessageOrderingIdempotencyInboxAndOutboxAreTransactional() {
        UserEntity sender = user();
        UserEntity recipient = user();
        DeviceEntity senderDevice = device(sender.getId());
        ChatEntity chat = chatService.createChat(sender.getId(), ChatType.DIRECT, null, "trace-chat", key());
        chatService.addMember(sender.getId(), chat.getId(), recipient.getId(), ChatMemberRole.MEMBER,
                "trace-member", key());

        UUID clientId = UUID.randomUUID();
        MessageEntity first = messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                senderDevice.getId(), clientId, "TEXT", "first", null, null, "trace-message",
                clientId.toString()));
        MessageEntity duplicate = messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                senderDevice.getId(), clientId, "TEXT", "first", null, null, "trace-message-retry",
                clientId.toString()));
        MessageEntity second = messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                senderDevice.getId(), UUID.randomUUID(), "TEXT", "reply", first.getMessageId(), null,
                "trace-message-2", key()));

        assertThat(duplicate.getMessageId()).isEqualTo(first.getMessageId());
        assertThat(messageRepository.findByChatIdOrderBySequenceNumberAsc(chat.getId()))
                .extracting(MessageEntity::getSequenceNumber).containsExactly(1L, 2L);
        assertThat(second.getReplyToMessageId()).isEqualTo(first.getMessageId());
        assertThat(memberRepository.findByIdUserIdAndLeftAtIsNullOrderByJoinedAtDesc(recipient.getId()))
                .hasSize(1);
        assertThat(inboxRepository.findByIdUserIdOrderBySequenceAsc(recipient.getId())).hasSize(2);
        assertThat(idempotencyRepository.findByUserIdAndIdempotencyKey(sender.getId(),
                first.getClientMessageId().toString())).isPresent();
        assertThat(outboxRepository.findAll()).anyMatch(event -> "message.created".equals(event.getEventType())
                && event.getPayload().get("sequence").asLong() == 1L);
    }

    @Test
    void messageVersionsReactionsReadStatesBlockedUsersAndAuditArePersisted() {
        UserEntity sender = user();
        UserEntity recipient = user();
        DeviceEntity device = device(sender.getId());
        ChatEntity chat = chatService.createChat(sender.getId(), ChatType.GROUP, "Engineering", "trace-chat", key());
        chatService.addMember(sender.getId(), chat.getId(), recipient.getId(), ChatMemberRole.MEMBER,
                "trace-member", key());
        MessageEntity message = messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                device.getId(), UUID.randomUUID(), "TEXT", "before", null, null, "trace-message", key()));

        MessageEntity edited = versionService.edit(sender.getId(), message.getMessageId(), "TEXT", "after",
                "trace-edit", key());
        reactionService.add(recipient.getId(), message.getMessageId(), "like", "trace-reaction-add", key());
        reactionService.remove(recipient.getId(), message.getMessageId(), "like", "trace-reaction-remove", key());
        readStateService.markRead(recipient.getId(), chat.getId(), edited.getSequenceNumber(), "trace-read", key());
        blockedUserService.block(sender.getId(), recipient.getId());

        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(edited.getStatus()).isEqualTo(MessageStatus.EDITED);
        assertThat(versionRepository.findByMessageIdOrderByVersionAsc(message.getMessageId()))
                .extracting(version -> version.getVersion()).containsExactly(2);
        assertThat(reactionRepository.findByIdMessageIdOrderByCreatedAtAsc(message.getMessageId())).isEmpty();
        assertThat(readStateRepository.findById(new com.uvya.apigateway.data.domain.ReadStateId(chat.getId(),
                recipient.getId()))).get().extracting(state -> state.getLastReadSequence()).isEqualTo(1L);
        assertThat(blockedUserRepository.existsBlock(sender.getId(), recipient.getId())).isTrue();
        assertThat(auditLogRepository.countByEventType("MESSAGE_CREATED")).isGreaterThanOrEqualTo(1);
        blockedUserService.unblock(sender.getId(), recipient.getId());
        assertThat(blockedUserRepository.existsBlock(sender.getId(), recipient.getId())).isFalse();
    }

    @Test
    void outboxPublisherMarksOnlySuccessfullyPublishedEvents() {
        UserEntity sender = user();
        DeviceEntity device = device(sender.getId());
        ChatEntity chat = chatService.createChat(sender.getId(), ChatType.DIRECT, null, "trace-chat", key());
        MessageEntity message = messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                device.getId(), UUID.randomUUID(), "TEXT", "publish", null, null, "trace-message", key()));
        var event = outboxRepository.findAll().stream()
                .filter(candidate -> candidate.getAggregateId().equals(message.getMessageId())).findFirst().orElseThrow();
        publicationService.publish(event.getEventId(), published -> { });
        assertThat(outboxRepository.findById(event.getEventId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void messageAndOutboxAreRolledBackTogetherWhenOutboxInsertFails() {
        UserEntity sender = user();
        DeviceEntity device = device(sender.getId());
        ChatEntity chat = chatService.createChat(sender.getId(), ChatType.DIRECT, null, "trace-chat", key());
        String duplicateOutboxKey = key();
        Instant now = Instant.now();
        outboxRepository.saveAndFlush(new OutboxEventEntity(UUID.randomUUID(), "message.created", 1, now,
                "existing-trace", duplicateOutboxKey, "MESSAGE", UUID.randomUUID(),
                objectMapper.createObjectNode()));

        assertThatThrownBy(() -> messageService.create(new MessageCreationCommand(chat.getId(), sender.getId(),
                device.getId(), UUID.randomUUID(), "TEXT", "must rollback", null, null,
                "trace-message", duplicateOutboxKey))).isInstanceOf(RuntimeException.class);

        assertThat(messageRepository.findByChatIdOrderBySequenceNumberAsc(chat.getId())).isEmpty();
        assertThat(idempotencyRepository.findByUserIdAndIdempotencyKey(sender.getId(), duplicateOutboxKey))
                .isEmpty();
    }

    private UserEntity user() {
        Instant now = Instant.now();
        return userRepository.saveAndFlush(new UserEntity(UUID.randomUUID(), UUID.randomUUID() + "@example.com",
                "test-password-hash", now));
    }

    private DeviceEntity device(UUID userId) {
        Instant now = Instant.now();
        return deviceRepository.saveAndFlush(new DeviceEntity(UUID.randomUUID(), userId, "test-device",
                "test-agent", "127.0.0.1", now));
    }

    private String key() { return UUID.randomUUID().toString(); }
}
