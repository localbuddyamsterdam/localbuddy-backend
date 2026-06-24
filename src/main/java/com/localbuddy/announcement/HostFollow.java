package com.localbuddy.announcement;

import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A traveler following a host, opting into that host's announcements. */
@Entity
@Table(name = "host_follows",
        uniqueConstraints = @UniqueConstraint(name = "uk_host_follow", columnNames = {"follower_user_id", "local_profile_id"}))
@Getter
@Setter
@NoArgsConstructor
public class HostFollow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_user_id", nullable = false)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_profile_id", nullable = false)
    private LocalProfile localProfile;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
