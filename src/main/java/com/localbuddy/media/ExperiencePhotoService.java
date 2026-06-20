package com.localbuddy.media;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.localprofile.LocalProfile;
import com.localbuddy.localprofile.LocalProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ExperiencePhotoService {

    private static final int MAX_PHOTOS_PER_EXPERIENCE = 20;

    private final ExperiencePhotoRepository photoRepository;
    private final ExperienceRepository experienceRepository;
    private final LocalProfileRepository localProfileRepository;
    private final MediaStorageProvider storageProvider;

    public ExperiencePhotoService(ExperiencePhotoRepository photoRepository,
                                  ExperienceRepository experienceRepository,
                                  LocalProfileRepository localProfileRepository,
                                  MediaStorageProvider storageProvider) {
        this.photoRepository = photoRepository;
        this.experienceRepository = experienceRepository;
        this.localProfileRepository = localProfileRepository;
        this.storageProvider = storageProvider;
    }

    @Transactional(readOnly = true)
    public List<ExperiencePhotoResponse> listPhotos(UUID experienceId) {
        return photoRepository.findByExperienceIdOrderBySortOrderAscCreatedAtAsc(experienceId)
                .stream().map(ExperiencePhotoResponse::from).toList();
    }

    @Transactional
    public ExperiencePhotoResponse uploadPhoto(UUID userId, UUID experienceId,
                                               byte[] data, String contentType, String filename, String caption) {
        Experience experience = requireOwnedExperience(userId, experienceId);
        if (data == null || data.length == 0) {
            throw new BadRequestException("Uploaded file is empty");
        }
        enforceLimit(experienceId);

        StoredObject stored = storageProvider.upload(data, contentType, filename);

        ExperiencePhoto photo = new ExperiencePhoto();
        photo.setExperience(experience);
        photo.setStorageKey(stored.storageKey());
        photo.setUrl(stored.url());
        photo.setContentType(contentType);
        photo.setSizeBytes((long) data.length);
        photo.setCaption(trimToNull(caption));
        return save(experienceId, photo);
    }

    @Transactional
    public ExperiencePhotoResponse registerPhotoUrl(UUID userId, UUID experienceId, RegisterPhotoUrlRequest request) {
        Experience experience = requireOwnedExperience(userId, experienceId);
        enforceLimit(experienceId);

        ExperiencePhoto photo = new ExperiencePhoto();
        photo.setExperience(experience);
        photo.setUrl(request.url().trim());
        photo.setCaption(trimToNull(request.caption()));
        return save(experienceId, photo);
    }

    @Transactional
    public void deletePhoto(UUID userId, UUID experienceId, UUID photoId) {
        requireOwnedExperience(userId, experienceId);
        ExperiencePhoto photo = requirePhoto(experienceId, photoId);

        boolean wasCover = photo.isCover();
        storageProvider.delete(photo.getStorageKey());
        photoRepository.delete(photo);

        if (wasCover) {
            List<ExperiencePhoto> remaining =
                    photoRepository.findByExperienceIdOrderBySortOrderAscCreatedAtAsc(experienceId);
            if (!remaining.isEmpty()) {
                remaining.get(0).setCover(true);
                photoRepository.save(remaining.get(0));
            }
        }
    }

    @Transactional
    public ExperiencePhotoResponse setCover(UUID userId, UUID experienceId, UUID photoId) {
        requireOwnedExperience(userId, experienceId);
        ExperiencePhoto target = requirePhoto(experienceId, photoId);

        List<ExperiencePhoto> all =
                photoRepository.findByExperienceIdOrderBySortOrderAscCreatedAtAsc(experienceId);
        for (ExperiencePhoto p : all) {
            p.setCover(p.getId().equals(photoId));
        }
        photoRepository.saveAll(all);
        return ExperiencePhotoResponse.from(target);
    }

    @Transactional
    public List<ExperiencePhotoResponse> reorder(UUID userId, UUID experienceId, ReorderPhotosRequest request) {
        requireOwnedExperience(userId, experienceId);
        List<ExperiencePhoto> all =
                photoRepository.findByExperienceIdOrderBySortOrderAscCreatedAtAsc(experienceId);

        List<ExperiencePhoto> ordered = new ArrayList<>();
        for (UUID id : request.photoIds()) {
            all.stream().filter(p -> p.getId().equals(id)).findFirst().ifPresent(ordered::add);
        }
        // Append any photos not referenced in the request, preserving their current order.
        for (ExperiencePhoto p : all) {
            if (ordered.stream().noneMatch(o -> o.getId().equals(p.getId()))) {
                ordered.add(p);
            }
        }

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSortOrder(i);
        }
        photoRepository.saveAll(ordered);
        return ordered.stream().map(ExperiencePhotoResponse::from).toList();
    }

    private ExperiencePhotoResponse save(UUID experienceId, ExperiencePhoto photo) {
        long existing = photoRepository.countByExperienceId(experienceId);
        photo.setSortOrder((int) existing);
        if (existing == 0) {
            photo.setCover(true);
        }
        return ExperiencePhotoResponse.from(photoRepository.save(photo));
    }

    private void enforceLimit(UUID experienceId) {
        if (photoRepository.countByExperienceId(experienceId) >= MAX_PHOTOS_PER_EXPERIENCE) {
            throw new BadRequestException("An experience can have at most " + MAX_PHOTOS_PER_EXPERIENCE + " photos");
        }
    }

    private Experience requireOwnedExperience(UUID userId, UUID experienceId) {
        LocalProfile localProfile = localProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Local profile not found"));
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));
        if (!experience.getLocalProfile().getId().equals(localProfile.getId())) {
            throw new ResourceNotFoundException("Experience not found");
        }
        return experience;
    }

    private ExperiencePhoto requirePhoto(UUID experienceId, UUID photoId) {
        ExperiencePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        if (!photo.getExperience().getId().equals(experienceId)) {
            throw new ResourceNotFoundException("Photo not found");
        }
        return photo;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
