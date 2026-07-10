package com.localbuddy.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class GoogleTokenVerifier implements SocialTokenVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={idToken}";

    private final String googleClientId;
    private final RestClient restClient = RestClient.create();

    public GoogleTokenVerifier(@Value("${app.social.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public VerifiedSocialUser verify(String idToken) {
        GoogleTokenInfo info;
        try {
            info = restClient.get()
                    .uri(TOKENINFO_URL, idToken)
                    .retrieve()
                    .body(GoogleTokenInfo.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
            }
            throw new BadRequestException("Invalid Google token");
        } catch (ResourceAccessException ex) {
            throw new ServiceUnavailableException("Google sign-in is temporarily unavailable. Please try again.");
        } catch (Exception ex) {
            throw new BadRequestException("Invalid Google token");
        }

        if (info == null || info.sub() == null) {
            throw new BadRequestException("Invalid Google token");
        }

        // When a client id is configured, the token must have been issued for this app.
        if (googleClientId != null && !googleClientId.isBlank()
                && !googleClientId.equals(info.aud())) {
            throw new BadRequestException("Google token was not issued for this application");
        }

        if (!"true".equalsIgnoreCase(info.emailVerified())) {
            throw new BadRequestException("Google email is not verified");
        }

        return new VerifiedSocialUser(SocialProvider.GOOGLE, info.sub(), info.email(), info.name());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleTokenInfo(
            String sub,
            String aud,
            String email,
            @JsonProperty("email_verified") String emailVerified,
            String name
    ) {
    }
}
