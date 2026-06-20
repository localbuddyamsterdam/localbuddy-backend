package com.localbuddy.media;

/** Result of storing a media object: the provider storage key and a fetchable URL. */
public record StoredObject(String storageKey, String url) {
}
