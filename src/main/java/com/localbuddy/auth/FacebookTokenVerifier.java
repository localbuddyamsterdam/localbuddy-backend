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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Verifies a Facebook Login access token and returns the verified identity.
 *
 * <p>When the app credentials are configured, verification is two steps:
 * <ol>
 *   <li><b>Audience check</b> — {@code /debug_token} confirms the token is valid
 *       and was issued for <em>this</em> app ({@code app_id} match). Without it,
 *       an access token minted for <em>any</em> other Facebook app the user
 *       authorized could be replayed here to log in as them (a token-swap /
 *       confused-deputy attack, since accounts are keyed by email). This mirrors
 *       the {@code aud} check {@link GoogleTokenVerifier} does for Google.</li>
 *   <li><b>Profile fetch</b> — {@code /me} reads id/name/email, signed with an
 *       {@code appsecret_proof} HMAC so a leaked token can't be used without the
 *       app secret.</li>
 * </ol>
 *
 * <p>When {@code app.social.facebook.app-id}/{@code app-secret} are blank (e.g.
 * local dev) both hardening steps are skipped and only the raw {@code /me} call
 * runs — the same env-gated "off until configured" pattern the rest of the app
 * uses. <b>Production must set both</b>, or the audience check does not run.
 */
@Component
public class FacebookTokenVerifier implements SocialTokenVerifier {

    private static final String GRAPH = "https://graph.facebook.com";
    private static final String ME_FIELDS = "/me?fields=id,name,email&access_token={token}";

    private final String appId;
    private final String appSecret;
    private final RestClient restClient = timeoutRestClient();

    public FacebookTokenVerifier(@Value("${app.social.facebook.app-id:}") String appId,
                                 @Value("${app.social.facebook.app-secret:}") String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
    }

    /** Short connect/read timeouts so an unresponsive graph.facebook.com fails fast instead of hanging
     * the login request thread — this verifier makes two synchronous Graph calls per login. */
    private static RestClient timeoutRestClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.FACEBOOK;
    }

    @Override
    public VerifiedSocialUser verify(String accessToken) {
        boolean appConfigured = appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();

        if (appConfigured) {
            assertTokenIssuedForThisApp(accessToken);
        }

        FacebookUser user = fetchProfile(accessToken, appConfigured);

        if (user == null || user.id() == null) {
            throw new BadRequestException("Invalid Facebook token");
        }
        if (user.email() == null || user.email().isBlank()) {
            throw new BadRequestException("Facebook account did not share an email address");
        }
        return new VerifiedSocialUser(SocialProvider.FACEBOOK, user.id(), user.email(), user.name());
    }

    /** Confirms via {@code /debug_token} that the token is valid and issued for our app. */
    private void assertTokenIssuedForThisApp(String accessToken) {
        DebugTokenResponse debug;
        try {
            debug = restClient.get()
                    .uri(GRAPH + "/debug_token?input_token={input}&access_token={app}",
                            accessToken, appId + "|" + appSecret)
                    .retrieve()
                    .body(DebugTokenResponse.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                throw new ServiceUnavailableException("Facebook sign-in is temporarily unavailable. Please try again.");
            }
            throw new BadRequestException("Invalid Facebook token");
        } catch (ResourceAccessException ex) {
            throw new ServiceUnavailableException("Facebook sign-in is temporarily unavailable. Please try again.");
        } catch (BadRequestException | ServiceUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Invalid Facebook token");
        }

        if (debug == null || debug.data() == null || !debug.data().isValid()
                || !appId.equals(debug.data().appId())) {
            throw new BadRequestException("Facebook token was not issued for this application");
        }
    }

    private FacebookUser fetchProfile(String accessToken, boolean appConfigured) {
        // Compute the proof outside the try so a config error fails closed, not as a 5xx.
        String proof = appConfigured ? appSecretProof(accessToken) : null;
        try {
            if (proof != null) {
                return restClient.get()
                        .uri(GRAPH + ME_FIELDS + "&appsecret_proof={proof}", accessToken, proof)
                        .retrieve()
                        .body(FacebookUser.class);
            }
            return restClient.get()
                    .uri(GRAPH + ME_FIELDS, accessToken)
                    .retrieve()
                    .body(FacebookUser.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                throw new ServiceUnavailableException("Facebook sign-in is temporarily unavailable. Please try again.");
            }
            throw new BadRequestException("Invalid Facebook token");
        } catch (ResourceAccessException ex) {
            throw new ServiceUnavailableException("Facebook sign-in is temporarily unavailable. Please try again.");
        } catch (Exception ex) {
            throw new BadRequestException("Invalid Facebook token");
        }
    }

    /** {@code HMAC-SHA256(access_token)} keyed with the app secret, hex-encoded (Facebook's {@code appsecret_proof}). */
    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            // A valid secret never triggers this; treat a broken one as a rejected login, not a 500.
            throw new BadRequestException("Invalid Facebook token");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FacebookUser(String id, String name, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DebugTokenResponse(DebugData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DebugData(@JsonProperty("app_id") String appId,
                     @JsonProperty("is_valid") boolean isValid,
                     @JsonProperty("user_id") String userId) {
    }
}
