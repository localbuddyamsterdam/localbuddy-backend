package com.localbuddy.auth;

/** Identity extracted from a verified social-provider token. */
public record VerifiedSocialUser(
        SocialProvider provider,
        String providerUserId,
        String email,
        String name
) {
}
