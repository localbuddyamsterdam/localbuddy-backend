package com.localbuddy.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StaffRoleAuditRepository extends JpaRepository<StaffRoleAudit, UUID> {

    List<StaffRoleAudit> findTop50ByOrderByCreatedAtDesc();
}
