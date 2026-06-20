package com.localbuddy.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/experiences/{experienceId}/photos")
@Tag(name = "Public Experience Photos", description = "Public, unauthenticated photo gallery for an experience")
public class PublicExperiencePhotoController {

    private final ExperiencePhotoService photoService;

    public PublicExperiencePhotoController(ExperiencePhotoService photoService) {
        this.photoService = photoService;
    }

    @Operation(summary = "List photos (public)", description = "Returns the ordered photo gallery for an experience.")
    @GetMapping
    public ResponseEntity<List<ExperiencePhotoResponse>> listPhotos(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(photoService.listPhotos(experienceId));
    }
}
