package com.localbuddy.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Existing customer↔host thread for an experience, if any (dedup on start). */
    @Query("""
            select c from Conversation c
            where c.type = com.localbuddy.messaging.ConversationType.CUSTOMER_HOST
              and c.experience.id = :experienceId
              and exists (select 1 from ConversationParticipant pc
                          where pc.conversation = c and pc.user.id = :customerId)
              and exists (select 1 from ConversationParticipant ph
                          where ph.conversation = c and ph.user.id = :hostId)
            """)
    Optional<Conversation> findCustomerHostConversation(@Param("customerId") UUID customerId,
                                                        @Param("hostId") UUID hostId,
                                                        @Param("experienceId") UUID experienceId);

    /** Conversations the user participates in, most recent activity first. */
    @Query("""
            select c from Conversation c
            where exists (select 1 from ConversationParticipant p
                          where p.conversation = c and p.user.id = :userId)
            order by coalesce(c.lastMessageAt, c.createdAt) desc
            """)
    List<Conversation> findMyConversations(@Param("userId") UUID userId);

    /** All conversations, most recent activity first (admin monitoring). */
    @Query("select c from Conversation c order by coalesce(c.lastMessageAt, c.createdAt) desc")
    List<Conversation> findAllByActivity();
}
