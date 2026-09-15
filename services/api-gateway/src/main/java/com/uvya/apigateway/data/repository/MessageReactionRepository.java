package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.MessageReactionEntity;
import com.uvya.apigateway.data.domain.MessageReactionId;

public interface MessageReactionRepository extends JpaRepository<MessageReactionEntity, MessageReactionId> {
    List<MessageReactionEntity> findByIdMessageIdOrderByCreatedAtAsc(UUID messageId);

    List<MessageReactionEntity> findByIdMessageIdAndIdUserIdOrderByCreatedAtAsc(UUID messageId, UUID userId);

    @Query("select r.id.reactionType as reactionType, count(r) as reactionCount "
            + "from MessageReactionEntity r where r.id.messageId = :messageId "
            + "group by r.id.reactionType order by r.id.reactionType")
    List<ReactionCount> countByMessageId(@Param("messageId") UUID messageId);

    interface ReactionCount {
        String getReactionType();
        long getReactionCount();
    }
}
