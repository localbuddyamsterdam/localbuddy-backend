package com.localbuddy.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    long countByConversationIdAndSenderUserIdNotAndReadAtIsNull(UUID conversationId, UUID senderUserId);

    @Modifying
    @Query("""
            update Message m set m.readAt = :now
            where m.conversation.id = :conversationId
              and m.senderUser.id <> :userId
              and m.readAt is null
            """)
    int markReadForRecipient(@Param("conversationId") UUID conversationId,
                             @Param("userId") UUID userId,
                             @Param("now") Instant now);
}
