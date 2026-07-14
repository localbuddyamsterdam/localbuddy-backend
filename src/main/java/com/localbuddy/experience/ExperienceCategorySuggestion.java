package com.localbuddy.experience;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A category a host proposed from the listing form. Reviewed by an admin in
 * Catalog → Categories; approving one is a manual "add category" action, not an
 * automatic conversion — see {@link CategorySuggestionService}.
 */
@Entity
@Table(name = "experience_category_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class ExperienceCategorySuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "suggested_name", nullable = false, length = 100)
    private String suggestedName;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    /** The user (host) who proposed it; nullable so the row survives account deletion. */
    @Column(name = "suggested_by_user_id")
    private UUID suggestedByUserId;

    /** Set once an admin creates a category from this suggestion. */
    @Column(name = "resulting_category_id")
    private UUID resultingCategoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        if (status == null) {
            status = SuggestionStatus.PENDING;
        }
    }
}
