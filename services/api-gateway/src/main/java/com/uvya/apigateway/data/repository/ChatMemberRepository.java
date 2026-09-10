package com.uvya.apigateway.data.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uvya.apigateway.data.domain.ChatMemberEntity;
import com.uvya.apigateway.data.domain.ChatMemberId;

public interface ChatMemberRepository extends JpaRepository<ChatMemberEntity, ChatMemberId> {
    @Query("select count(m) > 0 from ChatMemberEntity m"
            + " where m.id.chatId = :chatId and m.id.userId = :userId and m.leftAt is null")
    boolean isActiveMember(@Param("chatId") UUID chatId, @Param("userId") UUID userId);

    List<ChatMemberEntity> findByIdUserIdAndLeftAtIsNullOrderByJoinedAtDesc(UUID userId);

    List<ChatMemberEntity> findByIdChatIdAndLeftAtIsNullOrderByJoinedAtAsc(UUID chatId);
}
