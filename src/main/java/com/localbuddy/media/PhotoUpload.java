package com.localbuddy.media;

/** One image to upload — raw bytes + metadata, decoupled from the web layer. */
public record PhotoUpload(byte[] data, String contentType, String filename) {
}
