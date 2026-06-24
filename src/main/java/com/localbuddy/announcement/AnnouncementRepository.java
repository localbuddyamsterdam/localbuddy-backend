package com.localbuddy.announcement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    List<Announcement> findByLocalProfileIdOrderByCreatedAtDesc(UUID localProfileId);

    List<Announcement> findByLocalProfileIsNullOrderByCreatedAtDesc();

    List<Announcement> findAllByOrderByCreatedAtDesc();
}
