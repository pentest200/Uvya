package com.uvya.apigateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.uvya.apigateway.chat.service.ChatApplicationService;
import com.uvya.apigateway.chat.service.ChatAuthorizationException;
import com.uvya.apigateway.chat.service.ChatAuthorizationPolicy;
import com.uvya.apigateway.data.domain.ChatEntity;
import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberRole;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.ChatRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatServiceIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private ChatRepository chatRepository;
    @Autowired private ChatMemberRepository memberRepository;
    @Autowired private ChatApplicationService chatService;
    @Autowired private ChatAuthorizationPolicy policy;
    @MockBean private AuthRateLimiter rateLimiter;

    @Test
    void ownerAndAdminPermissionsAreEnforcedWhileNonMembersAreRejected() throws Exception {
        TestUser owner = register();
        TestUser admin = register();
        TestUser member = register();
        TestUser outsider = register();
        JsonNode created = create(owner, "GROUP", List.of(member.userId()));
        String chatId = created.get("chatId").asText();
        add(owner, UUID.fromString(chatId), admin, "ADMIN");

        mockMvc.perform(patch("/v1/chats/" + chatId).with(csrf())
                .header("Authorization", bearer(admin.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("renamed"));
        mockMvc.perform(post("/v1/chats/" + chatId + "/members").with(csrf())
                .header("Authorization", bearer(admin.accessToken())).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + outsider.userId() + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/chats/" + chatId)
                .header("Authorization", bearer(outsider.accessToken()))).andExpect(status().isForbidden());

        mockMvc.perform(delete("/v1/chats/" + chatId + "/members/" + member.userId()).with(csrf())
                .header("Authorization", bearer(admin.accessToken())).header("Idempotency-Key", key()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/chats/" + chatId + "/members?page=0&size=1")
                .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void restrictedAndBannedUsersCannotPostOrAccessChat() throws Exception {
        TestUser owner = register();
        TestUser restricted = register();
        JsonNode created = create(owner, "GROUP", List.of());
        UUID chatId = UUID.fromString(created.get("chatId").asText());
        add(owner, chatId, restricted, "RESTRICTED");

        assertThatThrownBy(() -> policy.authorize(context(restricted), chatRepository.findById(chatId).orElseThrow(),
                com.uvya.apigateway.chat.service.ChatAction.POST_MESSAGE))
                .isInstanceOf(ChatAuthorizationException.class);

        ChatMemberEntity member = memberRepository.findById(
                new com.uvya.apigateway.data.domain.ChatMemberId(chatId, restricted.userId())).orElseThrow();
        member.ban(java.time.Instant.now(), null);
        memberRepository.saveAndFlush(member);
        mockMvc.perform(get("/v1/chats/" + chatId)
                .header("Authorization", bearer(restricted.accessToken()))).andExpect(status().isForbidden());
    }

    @Test
    void channelPostingIsLimitedToModeratorsUnlessMemberPostingIsEnabled() throws Exception {
        TestUser owner = register();
        TestUser member = register();
        JsonNode created = create(owner, "CHANNEL", List.of());
        UUID chatId = UUID.fromString(created.get("chatId").asText());
        add(owner, chatId, member, "MEMBER");
        ChatEntity channel = chatRepository.findById(chatId).orElseThrow();

        assertThatThrownBy(() -> policy.authorize(context(member), channel,
                com.uvya.apigateway.chat.service.ChatAction.POST_MESSAGE))
                .isInstanceOf(ChatAuthorizationException.class);
        TestUser moderator = register();
        add(owner, chatId, moderator, "MODERATOR");
        assertThat(policy.authorize(context(moderator), channel,
                com.uvya.apigateway.chat.service.ChatAction.POST_MESSAGE).getRole())
                .isEqualTo(ChatMemberRole.MODERATOR);

        mockMvc.perform(patch("/v1/chats/" + chatId).with(csrf())
                .header("Authorization", bearer(owner.accessToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":{\"memberPostingEnabled\":true}}"))
                .andExpect(status().isOk());
        assertThat(policy.authorize(context(member), channel,
                com.uvya.apigateway.chat.service.ChatAction.POST_MESSAGE).getRole())
                .isEqualTo(ChatMemberRole.MEMBER);
    }

    @Test
    void membershipRaceAllowsOnlyOneConcurrentAdd() throws Exception {
        TestUser owner = register();
        TestUser target = register();
        UUID chatId = UUID.fromString(create(owner, "GROUP", List.of()).get("chatId").asText());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicBoolean start = new java.util.concurrent.atomic.AtomicBoolean(false);
        Future<Boolean> first = executor.submit(() -> concurrentAdd(owner, target, chatId, ready, start, key()));
        Future<Boolean> second = executor.submit(() -> concurrentAdd(owner, target, chatId, ready, start, key()));
        ready.await();
        start.set(true);
        int successes = result(first) + result(second);
        executor.shutdownNow();
        assertThat(successes).isEqualTo(1);
        assertThat(memberRepository.isActiveMember(chatId, target.userId())).isTrue();
    }

    @Test
    void chatListIsPaginatedWithoutMaterializingAllMemberships() throws Exception {
        TestUser owner = register();
        create(owner, "GROUP", List.of());
        create(owner, "CHANNEL", List.of());
        mockMvc.perform(get("/v1/chats?page=0&size=1")
                .header("Authorization", bearer(owner.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private boolean concurrentAdd(TestUser owner, TestUser target, UUID chatId, CountDownLatch ready,
            java.util.concurrent.atomic.AtomicBoolean start, String idempotencyKey) throws InterruptedException {
        ready.countDown();
        while (!start.get()) {
            Thread.yield();
        }
        try {
            chatService.addMember(context(owner), chatId,
                    new com.uvya.apigateway.chat.web.AddChatMemberRequest(target.userId(), ChatMemberRole.MEMBER),
                    idempotencyKey);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private int result(Future<Boolean> future) throws InterruptedException, ExecutionException {
        return future.get() ? 1 : 0;
    }

    private JsonNode create(TestUser user, String type, List<UUID> memberIds) throws Exception {
        String members = memberIds.stream().map(UUID::toString).map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/v1/chats").with(csrf()).header("Authorization", bearer(user.accessToken()))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatType\":\"" + type + "\",\"memberIds\":[" + members + "]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void add(TestUser actor, UUID chatId, TestUser target, String role) throws Exception {
        mockMvc.perform(post("/v1/chats/" + chatId + "/members").with(csrf())
                .header("Authorization", bearer(actor.accessToken())).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + target.userId() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isNoContent());
    }

    private TestUser register() throws Exception {
        String email = "chat-" + UUID.randomUUID() + "@example.test";
        String body = "{\"email\":\"" + email + "\",\"password\":\"correct-password\","
                + "\"deviceName\":\"chat-test-device\"}";
        MvcResult result = mockMvc.perform(post("/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        TestUser created = new TestUser(email, user.getId(), response.get("accessToken").asText(),
                UUID.fromString(response.get("sessionId").asText()));
        return created;
    }

    private ChatAccessContext context(TestUser user) {
        UUID deviceId = deviceRepository.findByUserIdOrderByLastSeenAtDesc(user.userId()).get(0).getId();
        return new ChatAccessContext(user.userId(), deviceId, user.sessionId(), key());
    }

    private String bearer(String token) { return "Bearer " + token; }

    private String key() { return UUID.randomUUID().toString(); }

    private record TestUser(String email, UUID userId, String accessToken, UUID sessionId) { }
}
