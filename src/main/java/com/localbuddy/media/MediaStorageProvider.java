package com.localbuddy.media;

/** Abstraction over a binary media store (Azure Blob by default). */
public interface MediaStorageProvider {

    /** Whether the provider has credentials/configuration to store uploads. */
    boolean isConfigured();

    /** Store the given bytes under the given key prefix (folder) and return its storage key and public URL. */
    StoredObject upload(String keyPrefix, byte[] data, String contentType, String originalFilename);

    /** Store the given bytes under the default "experiences" prefix. */
    default StoredObject upload(byte[] data, String contentType, String originalFilename) {
        return upload("experiences", data, contentType, originalFilename);
    }

    /** Remove a previously stored object. No-op if the key is null/blank or already gone. */
    void delete(String storageKey);
}
