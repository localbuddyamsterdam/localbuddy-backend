package com.localbuddy.experience;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.media.ImageUploadValidator;
import com.localbuddy.media.MediaStorageProvider;
import com.localbuddy.media.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ExperienceCategoryService {

    private final ExperienceCategoryRepository experienceCategoryRepository;
    private final MediaStorageProvider storageProvider;

    public ExperienceCategoryService(ExperienceCategoryRepository experienceCategoryRepository,
                                     MediaStorageProvider storageProvider) {
        this.experienceCategoryRepository = experienceCategoryRepository;
        this.storageProvider = storageProvider;
    }

    /**
     * Store an uploaded icon in blob storage and return its public URL, for the
     * admin to save as a category's imageUrl. Kept separate from create/update so
     * the icon can be uploaded before the category exists (create flow).
     */
    public String uploadIcon(byte[] data, String contentType, String filename) {
        ImageUploadValidator.validate(data, contentType);

        if (!storageProvider.isConfigured()) {
            throw new BadRequestException(
                    "Image uploads aren't configured on this environment — paste an image URL instead.");
        }

        StoredObject stored = storageProvider.upload("category-icons", data, contentType, filename);
        return stored.url();
    }

    @Transactional(readOnly = true)
    public List<ExperienceCategoryResponse> getActiveCategories() {
        return experienceCategoryRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExperienceCategoryResponse> getAllCategories() {
        return experienceCategoryRepository.findAllByOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExperienceCategoryResponse createCategory(CreateExperienceCategoryRequest request) {
        String name = request.name().trim();

        if (experienceCategoryRepository.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("A category with this name already exists");
        }

        ExperienceCategory category = new ExperienceCategory();
        category.setName(name);
        category.setSlug(generateUniqueSlug(name));
        category.setDescription(request.description() != null ? request.description().trim() : null);
        category.setImageUrl(normalizeBlankToNull(request.imageUrl()));
        category.setActive(true);
        category.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);

        return toResponse(experienceCategoryRepository.save(category));
    }

    @Transactional
    public ExperienceCategoryResponse updateCategory(UUID categoryId, UpdateExperienceCategoryRequest request) {
        ExperienceCategory category = experienceCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience category not found"));

        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().trim();
            if (!name.equalsIgnoreCase(category.getName())
                    && experienceCategoryRepository.existsByNameIgnoreCase(name)) {
                throw new BadRequestException("A category with this name already exists");
            }
            category.setName(name);
        }
        if (request.description() != null) {
            category.setDescription(normalizeBlankToNull(request.description()));
        }
        if (request.imageUrl() != null) {
            category.setImageUrl(normalizeBlankToNull(request.imageUrl()));
        }
        if (request.displayOrder() != null) {
            category.setDisplayOrder(request.displayOrder());
        }

        return toResponse(experienceCategoryRepository.save(category));
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public ExperienceCategoryResponse setCategoryActive(UUID categoryId, boolean active) {
        ExperienceCategory category = experienceCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience category not found"));

        category.setActive(active);

        return toResponse(experienceCategoryRepository.save(category));
    }

    private ExperienceCategoryResponse toResponse(ExperienceCategory category) {
        return new ExperienceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getImageUrl(),
                category.isActive(),
                category.getDisplayOrder()
        );
    }

    private String generateUniqueSlug(String name) {
        String baseSlug = slugify(name);
        String candidate = baseSlug;
        int counter = 1;

        while (experienceCategoryRepository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + counter;
            counter++;
        }

        return candidate;
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
