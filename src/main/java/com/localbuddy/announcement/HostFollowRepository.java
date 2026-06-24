package com.localbuddy.announcement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HostFollowRepository extends JpaRepository<HostFollow, UUID> {

    Optional<HostFollow> findByFollowerIdAndLocalProfileId(UUID followerId, UUID localProfileId);

    boolean existsByFollowerIdAndLocalProfileId(UUID followerId, UUID localProfileId);

    List<HostFollow> findByLocalProfileId(UUID localProfileId);

    List<HostFollow> findByFollowerIdOrderByCreatedAtDesc(UUID followerId);

    long countByLocalProfileId(UUID localProfileId);
}
