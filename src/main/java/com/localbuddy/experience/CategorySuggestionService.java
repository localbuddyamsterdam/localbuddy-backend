package com.localbuddy.experience;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Host category suggestions. Hosts propose a name; admins review the queue and
 * decide whether to add it as a real category. Approving/dismissing only records
 * the decision — the admin still creates the category by hand (which then links
 * back here via {@link #resolve}).
 */
@Service
public class CategorySuggestionService {

    private final ExperienceCategorySuggestionRepository suggestionRepository;
    private final ExperienceCategoryRepository categoryRepository;

    public CategorySuggestionService(ExperienceCategorySuggestionRepository suggestionRepository,
                                     ExperienceCategoryRepository categoryRepository) {
        this.suggestionRepository = suggestionRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public CategorySuggestionResponse submit(UUID userId, CreateCategorySuggestionRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("Category name is required");
        }

        // Already a real category → nothing to suggest, the host can just select it.
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("That category already exists — you can select it directly.");
        }

        // Collapse duplicate pending proposals for the same name (idempotent-ish).
        if (suggestionRepository.existsBySuggestedNameIgnoreCaseAndStatus(name, SuggestionStatus.PENDING)) {
            throw new BadRequestException("That category has already been suggested and is awaiting review.");
        }

        ExperienceCategorySuggestion suggestion = new ExperienceCategorySuggestion();
        suggestion.setSuggestedName(name);
        suggestion.setNote(normalizeBlankToNull(request.note()));
        suggestion.setStatus(SuggestionStatus.PENDING);
        suggestion.setSuggestedByUserId(userId);

        return toResponse(suggestionRepository.save(suggestion));
    }

    @Transactional(readOnly = true)
    public List<CategorySuggestionResponse> list(SuggestionStatus status) {
        List<ExperienceCategorySuggestion> rows = (status == null)
                ? suggestionRepository.findAllByOrderByCreatedAtDesc()
                : suggestionRepository.findByStatusOrderByCreatedAtDesc(status);

        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return suggestionRepository.countByStatus(SuggestionStatus.PENDING);
    }

    @Transactional
    public CategorySuggestionResponse dismiss(UUID suggestionId) {
        ExperienceCategorySuggestion suggestion = getOrThrow(suggestionId);
        suggestion.setStatus(SuggestionStatus.DISMISSED);
        suggestion.setReviewedAt(Instant.now());
        return toResponse(suggestionRepository.save(suggestion));
    }

    /** Mark a suggestion approved and link it to the category an admin created from it. */
    @Transactional
    public CategorySuggestionResponse resolve(UUID suggestionId, UUID resultingCategoryId) {
        ExperienceCategorySuggestion suggestion = getOrThrow(suggestionId);

        if (resultingCategoryId != null && !categoryRepository.existsById(resultingCategoryId)) {
            throw new BadRequestException("Linked category does not exist");
        }

        suggestion.setStatus(SuggestionStatus.APPROVED);
        suggestion.setResultingCategoryId(resultingCategoryId);
        suggestion.setReviewedAt(Instant.now());
        return toResponse(suggestionRepository.save(suggestion));
    }

    private ExperienceCategorySuggestion getOrThrow(UUID id) {
        return suggestionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category suggestion not found"));
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CategorySuggestionResponse toResponse(ExperienceCategorySuggestion s) {
        return new CategorySuggestionResponse(
                s.getId(),
                s.getSuggestedName(),
                s.getNote(),
                s.getStatus(),
                s.getSuggestedByUserId(),
                s.getResultingCategoryId(),
                s.getCreatedAt(),
                s.getReviewedAt()
        );
    }
}
