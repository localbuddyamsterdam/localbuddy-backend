package com.localbuddy.media;

import com.localbuddy.common.exception.BadRequestException;

import java.util.Set;

/** Shared validation for uploaded images (host profile pics and experience photos). */
public final class ImageUploadValidator {

    /** Maximum accepted image size in bytes (5 MB). Keep in sync with spring.servlet.multipart.max-file-size. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private ImageUploadValidator() {
    }

    public static void validate(byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            throw new BadRequestException("Uploaded file is empty");
        }
        if (data.length > MAX_BYTES) {
            throw new BadRequestException("Image exceeds the maximum size of 5 MB");
        }
        String normalized = contentType == null ? "" : contentType.toLowerCase().trim();
        if (!ALLOWED_CONTENT_TYPES.contains(normalized)) {
            throw new BadRequestException("Unsupported image type. Allowed: JPEG, PNG, WebP, GIF");
        }
    }
}
