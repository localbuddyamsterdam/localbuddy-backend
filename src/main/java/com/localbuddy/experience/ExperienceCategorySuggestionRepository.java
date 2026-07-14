package com.localbuddy.experience;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExperienceCategorySuggestionRepository
        extends JpaRepository<ExperienceCategorySuggestion, UUID> {

    List<ExperienceCategorySuggestion> findAllByOrderByCreatedAtDesc();

    List<ExperienceCategorySuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    long countByStatus(SuggestionStatus status);

    boolean existsBySuggestedNameIgnoreCaseAndStatus(String suggestedName, SuggestionStatus status);
}
