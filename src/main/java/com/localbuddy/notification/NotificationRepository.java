package com.localbuddy.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByDedupeKey(String dedupeKey);

    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status);

    List<Notification> findTop20ByStatusOrderByCreatedAtAsc(NotificationStatus status);

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);

    List<Notification> findByRecipientUserIdAndChannelOrderByCreatedAtDesc(
            UUID recipientUserId,
            NotificationChannel channel
    );

    List<Notification> findByRecipientUserIdAndChannelAndReadAtIsNull(
            UUID recipientUserId,
            NotificationChannel channel
    );

    List<Notification> findByRelatedEntityTypeAndRelatedEntityIdOrderByCreatedAtDesc(
            String relatedEntityType,
            UUID relatedEntityId
    );

    @Query("""
            select n.id
            from Notification n
            where n.status = :status
            order by n.createdAt asc
            """)
    List<UUID> findPendingNotificationIds(@Param("status") NotificationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    Optional<Notification> findByIdForUpdate(@Param("id") UUID id);
}