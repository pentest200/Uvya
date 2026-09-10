package com.uvya.apigateway.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uvya.apigateway.auth.domain.UserEntity;
import com.uvya.apigateway.auth.repository.DeviceRepository;
import com.uvya.apigateway.auth.repository.UserRepository;
import com.uvya.apigateway.auth.service.AuthRateLimiter;
import com.uvya.apigateway.chat.service.ChatAccessContext;
import com.uvya.apigateway.data.repository.MessageRepository;
import com.uvya.apigateway.message.service.MessageApplicationService;
import com.uvya.apigateway.message.web.SendMessageRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageServiceIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MessageApplicationService messageService;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void sendIsIdempotentAndRejectsClientIdReuseWithDifferentPayload() throws Exception {
        TestUser user = register();
        UUID chatId = createChat(user);
        UUID clientId = UUID.randomUUID();
        send(user, chatId, clientId, "hello", key()).andExpect(status().isCreated());
        send(user, chatId, clientId, "hello", key()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequence").value(1));
        send(user, chatId, clientId, "different", key()).andExpect(status().isConflict());
        assertThat(messageRepository.findByChatIdOrderBySequenceNumberAsc(chatId)).hasSize(1);
    }

    @Test
    void historyUsesSequenceCursorAndDeletedMessagesAreTombstones() throws Exception {
        TestUser user = register();
        UUID chatId = createChat(user);
        send(user, chatId, UUID.randomUUID(), "one", key());
        send(user, chatId, UUID.randomUUID(), "two", key());
        MvcResult third = send(user, chatId, UUID.randomUUID(), "three", key()).andReturn();
        mockMvc.perform(get("/v1/chats/" + chatId + "/messages?size=2")
                .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].sequence").value(2))
                .andExpect(jsonPath("$.messages[1].sequence").value(3))
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextCursor").value("2"));
        mockMvc.perform(get("/v1/chats/" + chatId + "/messages?size=2&before=2")
                .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].sequence").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        UUID messageId = UUID.fromString(objectMapper.readTree(third.getResponse().getContentAsString())
                .get("messageId").asText());
        mockMvc.perform(delete("/v1/chats/" + chatId + "/messages/" + messageId).with(csrf())
                .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void onlySenderCanEditAndDeleteUnlessModerator() throws Exception {
        TestUser owner = register();
        TestUser member = register();
        UUID chatId = createChat(owner, member.userId());
        JsonNode sent = objectMapper.readTree(send(owner, chatId, UUID.randomUUID(), "original", key())
                .andReturn().getResponse().getContentAsString());
        String messageId = sent.get("messageId").asText();
        mockMvc.perform(patch("/v1/chats/" + chatId + "/messages/" + messageId).with(csrf())
                .header("Authorization", bearer(member.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"text\",\"body\":\"hijacked\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/chats/" + chatId + "/messages/" + messageId).with(csrf())
                .header("Authorization", bearer(member.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/v1/chats/" + chatId + "/messages/" + messageId).with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"text\",\"body\":\"edited\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void forwardingRequiresSourceVisibilityAndPersistsOnlyReference() throws Exception {
        TestUser user = register();
        UUID sourceChat = createChat(user);
        UUID targetChat = createChat(user);
        JsonNode source = objectMapper.readTree(send(user, sourceChat, UUID.randomUUID(), "forward me", key())
                .andReturn().getResponse().getContentAsString());
        send(user, targetChat, UUID.randomUUID(), "forwarded", key(), source.get("messageId").asText())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.forwardedFromMessageId")
                        .value(source.get("messageId").asText()));
    }

    @Test
    void directServiceAllocatesDistinctSequencesForEachPersistedMessage() throws Exception {
        TestUser user = register();
        UUID chatId = createChat(user);
        ChatAccessContext context = context(user);
        messageService.send(context, chatId, new SendMessageRequest(UUID.randomUUID(), "text", "a", null, null,
                List.of()), key());
        messageService.send(context, chatId, new SendMessageRequest(UUID.randomUUID(), "text", "b", null, null,
                List.of()), key());
        assertThat(messageRepository.findByChatIdOrderBySequenceNumberAsc(chatId)).extracting("sequenceNumber")
                .containsExactly(1L, 2L);
    }

    private org.springframework.test.web.servlet.ResultActions send(TestUser user, UUID chatId, UUID clientId,
            String body, String idempotencyKey) throws Exception {
        return send(user, chatId, clientId, body, idempotencyKey, null);
    }

    private org.springframework.test.web.servlet.ResultActions send(TestUser user, UUID chatId, UUID clientId,
            String body, String idempotencyKey, String forwardedFrom) throws Exception {
        String forward = forwardedFrom == null ? "" : ",\"forwardedFromMessageId\":\"" + forwardedFrom + "\"";
        return mockMvc.perform(post("/v1/chats/" + chatId + "/messages").with(csrf())
                .header("Authorization", bearer(user.accessToken())).header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"" + clientId + "\",\"type\":\"text\",\"body\":\""
                        + body + "\",\"attachments\":[]" + forward + "}"));
    }

    private UUID createChat(TestUser user, UUID... memberIds) throws Exception {
        String ids = java.util.Arrays.stream(memberIds).map(UUID::toString)
                .map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/v1/chats").with(csrf())
                .header("Authorization", bearer(user.accessToken())).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatType\":\"GROUP\",\"memberIds\":[" + ids + "]}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("chatId").asText());
    }

    private TestUser register() throws Exception {
        String email = "message-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-password\","
                        + "\"deviceName\":\"message-test-device\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        return new TestUser(user.getId(), response.get("accessToken").asText(),
                UUID.fromString(response.get("sessionId").asText()));
    }

    private ChatAccessContext context(TestUser user) {
        UUID deviceId = deviceRepository.findByUserIdOrderByLastSeenAtDesc(user.userId()).get(0).getId();
        return new ChatAccessContext(user.userId(), deviceId, user.sessionId(), key());
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String key() { return UUID.randomUUID().toString(); }

    private record TestUser(UUID userId, String accessToken, UUID sessionId) { }
}
