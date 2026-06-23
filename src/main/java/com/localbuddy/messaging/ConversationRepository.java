package com.localbuddy.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByLoggedInUserIdAndHostUserIdAndExperienceId(
            UUID loggedInUserId, UUID hostUserId, UUID experienceId);

    @Query("""
            select c from Conversation c
            where c.loggedInUser.id = :userId or c.hostUser.id = :userId
            order by coalesce(c.lastMessageAt, c.createdAt) desc
            """)
    List<Conversation> findMyConversations(@Param("userId") UUID userId);
}
