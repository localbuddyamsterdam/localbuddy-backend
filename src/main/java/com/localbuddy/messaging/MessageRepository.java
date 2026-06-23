package com.localbuddy.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Messages in a conversation not sent by the viewer and newer than their read cursor. */
    @Query("""
            select count(m) from Message m
            where m.conversation.id = :conversationId
              and m.senderUser.id <> :userId
              and (:since is null or m.createdAt > :since)
            """)
    long countUnread(@Param("conversationId") UUID conversationId,
                     @Param("userId") UUID userId,
                     @Param("since") Instant since);
}
