package com.localbuddy.auth;

/** Verifies a social-provider token and returns the verified identity. */
public interface SocialTokenVerifier {

    SocialProvider provider();

    VerifiedSocialUser verify(String token);
}
