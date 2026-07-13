package com.localbuddy.localprofile;

import com.localbuddy.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocalProfileRepository extends JpaRepository<LocalProfile, UUID> {

    Optional<LocalProfile> findByUser(User user);

    Optional<LocalProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    List<LocalProfile> findByHostCityIgnoreCaseAndApprovalStatus(String hostCity, LocalApprovalStatus approvalStatus);

    List<LocalProfile> findByApprovalStatus(LocalApprovalStatus approvalStatus);
    long countByApprovalStatus(LocalApprovalStatus approvalStatus);

    // Paginated variants for public browsing.
    Page<LocalProfile> findByApprovalStatus(LocalApprovalStatus approvalStatus, Pageable pageable);
    Page<LocalProfile> findByHostCityIgnoreCaseAndApprovalStatus(
            String hostCity, LocalApprovalStatus approvalStatus, Pageable pageable);
}