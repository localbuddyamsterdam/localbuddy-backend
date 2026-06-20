package com.localbuddy.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.localbuddy.common.exception.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FacebookTokenVerifier implements SocialTokenVerifier {

    private static final String GRAPH_ME_URL =
            "https://graph.facebook.com/me?fields=id,name,email&access_token={accessToken}";

    private final RestClient restClient = RestClient.create();

    @Override
    public SocialProvider provider() {
        return SocialProvider.FACEBOOK;
    }

    @Override
    public VerifiedSocialUser verify(String accessToken) {
        FacebookUser user;
        try {
            user = restClient.get()
                    .uri(GRAPH_ME_URL, accessToken)
                    .retrieve()
                    .body(FacebookUser.class);
        } catch (Exception ex) {
            throw new BadRequestException("Invalid Facebook token");
        }

        if (user == null || user.id() == null) {
            throw new BadRequestException("Invalid Facebook token");
        }

        if (user.email() == null || user.email().isBlank()) {
            throw new BadRequestException("Facebook account did not share an email address");
        }

        return new VerifiedSocialUser(SocialProvider.FACEBOOK, user.id(), user.email(), user.name());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FacebookUser(String id, String name, String email) {
    }
}
