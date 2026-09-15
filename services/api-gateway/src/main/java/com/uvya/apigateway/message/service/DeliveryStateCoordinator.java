package com.uvya.apigateway.message.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uvya.apigateway.data.domain.DeliveryState;
import com.uvya.apigateway.data.domain.UserInboxEntity;
import com.uvya.apigateway.data.domain.UserInboxId;
import com.uvya.apigateway.data.repository.ChatMemberRepository;
import com.uvya.apigateway.data.repository.UserInboxRepository;

@Service
public class DeliveryStateCoordinator {
    private final UserInboxRepository inboxRepository;
    private final ChatMemberRepository memberRepository;

    public DeliveryStateCoordinator(UserInboxRepository inboxRepository, ChatMemberRepository memberRepository) {
        this.inboxRepository = inboxRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPending(UUID userId, UUID messageId, UUID chatId, long sequence, Instant receivedAt,
            int maxAttempts) {
        UserInboxId id = new UserInboxId(userId, messageId);
        UserInboxEntity inbox = inboxRepository.findByIdForUpdate(id).orElse(null);
        if (inbox == null) {
            if (!memberRepository.isActiveMember(chatId, userId)) {
                return false;
            }
            inbox = new UserInboxEntity(userId, messageId, chatId, sequence, receivedAt);
        }
        if (inbox.getDeliveryState() == DeliveryState.DELIVERED
                || inbox.getDeliveryState() == DeliveryState.READ) {
            return false;
        }
        if (inbox.getDeliveryAttempts() >= maxAttempts) {
            inbox.markFailed();
            inboxRepository.save(inbox);
            return false;
        }
        inbox.markPending(Instant.now());
        inboxRepository.save(inbox);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID userId, UUID messageId) {
        inboxRepository.findByIdForUpdate(new UserInboxId(userId, messageId)).ifPresent(inbox -> {
            inbox.markFailed();
            inboxRepository.save(inbox);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptFailure(UUID userId, UUID messageId, int maxAttempts) {
        inboxRepository.findByIdForUpdate(new UserInboxId(userId, messageId)).ifPresent(inbox -> {
            if (inbox.getDeliveryAttempts() >= maxAttempts) {
                inbox.markFailed();
                inboxRepository.save(inbox);
            }
        });
    }
}
