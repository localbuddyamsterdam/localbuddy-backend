package com.localbuddy.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cached AI digest of an experience's visible traveler reviews ("what guests say").
 * Regenerated when the review count changes or the cache entry gets old.
 */
@Entity
@Table(name = "experience_review_summaries")
@Getter
@Setter
@NoArgsConstructor
public class ExperienceReviewSummary {

    @Id
    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "highlights", columnDefinition = "jsonb")
    private List<String> highlights = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concerns", columnDefinition = "jsonb")
    private List<String> concerns = new ArrayList<>();

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount = 0;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "model", length = 60)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
