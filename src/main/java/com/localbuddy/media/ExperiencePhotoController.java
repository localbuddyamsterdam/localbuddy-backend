package com.localbuddy.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/experiences/{experienceId}/photos")
@Tag(name = "Experience Photos", description = "Manage and view the photo gallery for an experience")
@SecurityRequirement(name = "bearerAuth")
public class ExperiencePhotoController {

    private final ExperiencePhotoService photoService;

    public ExperiencePhotoController(ExperiencePhotoService photoService) {
        this.photoService = photoService;
    }

    @Operation(summary = "List photos", description = "Public: returns the ordered photo gallery for an experience.")
    @GetMapping
    public ResponseEntity<List<ExperiencePhotoResponse>> listPhotos(@PathVariable UUID experienceId) {
        return ResponseEntity.ok(photoService.listPhotos(experienceId));
    }

    @Operation(summary = "Upload a photo",
            description = "Host: uploads an image file to blob storage and attaches it to the experience.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExperiencePhotoResponse> uploadPhoto(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption
    ) throws IOException {
        UUID userId = UUID.fromString(authentication.getName());
        ExperiencePhotoResponse response = photoService.uploadPhoto(
                userId, experienceId, file.getBytes(), file.getContentType(), file.getOriginalFilename(), caption);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Upload multiple photos",
            description = "Host: uploads several images at once (multipart field 'files') to blob storage and attaches them to the experience.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Photos uploaded"),
            @ApiResponse(responseCode = "400", description = "No files, invalid/unsupported/oversized image, or exceeds the per-experience photo limit"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Experience not found or not owned by you"),
            @ApiResponse(responseCode = "413", description = "A file exceeds the maximum upload size")
    })
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<ExperiencePhotoResponse>> uploadPhotos(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @RequestParam("files") MultipartFile[] files
    ) throws IOException {
        UUID userId = UUID.fromString(authentication.getName());
        List<PhotoUpload> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            uploads.add(new PhotoUpload(file.getBytes(), file.getContentType(), file.getOriginalFilename()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(photoService.uploadPhotos(userId, experienceId, uploads));
    }

    @Operation(summary = "Register an external photo URL",
            description = "Host: attaches an externally-hosted image URL (e.g. uploaded directly to blob via SAS).")
    @PostMapping("/external")
    public ResponseEntity<ExperiencePhotoResponse> registerPhotoUrl(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @Valid @RequestBody RegisterPhotoUrlRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(photoService.registerPhotoUrl(userId, experienceId, request));
    }

    @Operation(summary = "Update a photo caption", description = "Host: updates the caption of a gallery photo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photo updated"),
            @ApiResponse(responseCode = "400", description = "Invalid caption"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Photo or experience not found")
    })
    @PutMapping("/{photoId}")
    public ResponseEntity<ExperiencePhotoResponse> updatePhoto(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @PathVariable UUID photoId,
            @Valid @RequestBody UpdateExperiencePhotoRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(photoService.updateCaption(userId, experienceId, photoId, request));
    }

    @Operation(summary = "Delete a photo", description = "Host: removes a photo from the experience gallery.")
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> deletePhoto(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @PathVariable UUID photoId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        photoService.deletePhoto(userId, experienceId, photoId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set cover photo", description = "Host: marks a photo as the cover image.")
    @PutMapping("/{photoId}/cover")
    public ResponseEntity<ExperiencePhotoResponse> setCover(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @PathVariable UUID photoId
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(photoService.setCover(userId, experienceId, photoId));
    }

    @Operation(summary = "Reorder photos", description = "Host: sets the display order of the gallery.")
    @PutMapping("/order")
    public ResponseEntity<List<ExperiencePhotoResponse>> reorder(
            Authentication authentication,
            @PathVariable UUID experienceId,
            @Valid @RequestBody ReorderPhotosRequest request
    ) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(photoService.reorder(userId, experienceId, request));
    }
}
