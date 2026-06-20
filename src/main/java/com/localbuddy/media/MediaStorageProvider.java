package com.localbuddy.media;

/** Abstraction over a binary media store (Azure Blob by default). */
public interface MediaStorageProvider {

    /** Whether the provider has credentials/configuration to store uploads. */
    boolean isConfigured();

    /** Store the given bytes and return its storage key and public URL. */
    StoredObject upload(byte[] data, String contentType, String originalFilename);

    /** Remove a previously stored object. No-op if the key is null/blank or already gone. */
    void delete(String storageKey);
}
