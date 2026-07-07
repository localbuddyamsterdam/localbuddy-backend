package com.localbuddy.media;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.localbuddy.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Azure Blob Storage media provider. Lazily builds the client so the application
 * starts cleanly when blob storage is not configured (dev/test).
 */
@Component
public class AzureBlobStorageProvider implements MediaStorageProvider {

    private final String connectionString;
    private final String containerName;
    private final String publicBaseUrl;

    private volatile BlobContainerClient containerClient;

    public AzureBlobStorageProvider(
            @Value("${app.media.azure.connection-string:}") String connectionString,
            @Value("${app.media.azure.container:experience-photos}") String containerName,
            @Value("${app.media.azure.public-base-url:}") String publicBaseUrl
    ) {
        this.connectionString = connectionString;
        this.containerName = containerName;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public boolean isConfigured() {
        return connectionString != null && !connectionString.trim().isEmpty();
    }

    @Override
    public StoredObject upload(String keyPrefix, byte[] data, String contentType, String originalFilename) {
        requireConfigured();
        try {
            String key = normalizePrefix(keyPrefix) + UUID.randomUUID() + extension(originalFilename, contentType);
            BlobClient blob = container().getBlobClient(key);
            blob.upload(BinaryData.fromBytes(data), true);
            if (contentType != null && !contentType.isBlank()) {
                blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
            }
            return new StoredObject(key, resolveUrl(key, blob));
        } catch (Exception ex) {
            throw new BadRequestException("Unable to upload media: " + ex.getMessage());
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || !isConfigured()) {
            return;
        }
        try {
            container().getBlobClient(storageKey).deleteIfExists();
        } catch (Exception ignored) {
            // Best-effort cleanup; do not fail the request if the blob is already gone.
        }
    }

    private BlobContainerClient container() {
        BlobContainerClient local = containerClient;
        if (local == null) {
            synchronized (this) {
                local = containerClient;
                if (local == null) {
                    BlobServiceClient service = new BlobServiceClientBuilder()
                            .connectionString(connectionString)
                            .buildClient();
                    local = service.getBlobContainerClient(containerName);
                    if (!local.exists()) {
                        local.create();
                    }
                    containerClient = local;
                }
            }
        }
        return local;
    }

    private String resolveUrl(String key, BlobClient blob) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
            return base + key;
        }
        return blob.getBlobUrl();
    }

    private String normalizePrefix(String keyPrefix) {
        String p = (keyPrefix == null || keyPrefix.isBlank()) ? "experiences" : keyPrefix.trim();
        p = p.replaceAll("^/+", "").replaceAll("/+$", "");
        return p.isEmpty() ? "experiences/" : p + "/";
    }

    private String extension(String originalFilename, String contentType) {
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0 && dot < originalFilename.length() - 1) {
                return originalFilename.substring(dot).toLowerCase();
            }
        }
        if (contentType != null) {
            return switch (contentType) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                default -> "";
            };
        }
        return "";
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BadRequestException(
                    "Media upload is not available yet (Azure Blob storage is not configured). "
                            + "Use the external-URL endpoint instead.");
        }
    }
}
