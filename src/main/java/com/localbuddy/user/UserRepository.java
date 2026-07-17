package com.localbuddy.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByRole(UserRole role);

    long countByRoleAndStatus(UserRole role, UserStatus status);

    /**
     * Locks (SELECT … FOR UPDATE) every active user holding the given role for the
     * duration of the transaction. Used to serialize admin-team role changes so two
     * super admins demoting each other concurrently can't race the "at least one
     * super admin remains" invariant past each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.status = :status")
    List<User> lockAllByRoleAndStatus(@Param("role") UserRole role, @Param("status") UserStatus status);
}