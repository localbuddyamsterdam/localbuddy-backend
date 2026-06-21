package com.localbuddy.wallet;

import com.localbuddy.common.exception.BadRequestException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Wallet "Save to Wallet" provider. Builds a signed JWT (RS256) that encodes
 * a generic pass object and returns a {@code https://pay.google.com/gp/v/save/<jwt>}
 * link. Config-guarded: dormant until the issuer id, class id, and service-account
 * credentials are set under {@code app.wallet.google.*}.
 *
 * <p>Setup (one-time, by the issuer): create a Google Wallet API issuer account and a
 * Generic pass <em>class</em> whose id is {@code <issuerId>.<classSuffix>}, and set that
 * as {@code app.wallet.google.class-id}.
 */
@Service
public class GoogleWalletService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final String issuerId;
    private final String classId;
    private final String serviceAccountEmail;
    private final String serviceAccountPrivateKey;
    private final String origin;

    public GoogleWalletService(
            @Value("${app.wallet.google.issuer-id:}") String issuerId,
            @Value("${app.wallet.google.class-id:}") String classId,
            @Value("${app.wallet.google.service-account-email:}") String serviceAccountEmail,
            @Value("${app.wallet.google.service-account-private-key:}") String serviceAccountPrivateKey,
            @Value("${app.wallet.google.origin:}") String origin) {
        this.issuerId = issuerId;
        this.classId = classId;
        this.serviceAccountEmail = serviceAccountEmail;
        this.serviceAccountPrivateKey = serviceAccountPrivateKey;
        this.origin = origin;
    }

    public boolean isConfigured() {
        return notBlank(issuerId) && notBlank(classId)
                && notBlank(serviceAccountEmail) && notBlank(serviceAccountPrivateKey);
    }

    public String buildSaveUrl(WalletPassData data) {
        if (!isConfigured()) {
            throw new BadRequestException("Google Wallet is not configured");
        }
        try {
            Map<String, Object> genericObject = buildGenericObject(data);

            Map<String, Object> payload = Map.of("genericObjects", List.of(genericObject));

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", serviceAccountEmail);
            claims.put("aud", "google");
            claims.put("typ", "savetowallet");
            claims.put("payload", payload);
            if (notBlank(origin)) {
                claims.put("origins", List.of(origin));
            }

            String jwt = Jwts.builder()
                    .claims(claims)
                    .signWith(parsePrivateKey(serviceAccountPrivateKey), Jwts.SIG.RS256)
                    .compact();

            return "https://pay.google.com/gp/v/save/" + jwt;
        } catch (Exception ex) {
            throw new BadRequestException("Unable to build Google Wallet link: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildGenericObject(WalletPassData data) {
        String objectId = issuerId + "." + sanitize(data.bookingReference());

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", objectId);
        object.put("classId", classId);
        object.put("state", "ACTIVE");
        object.put("cardTitle", localized("LocalBuddy"));
        object.put("header", localized(data.experienceTitle()));

        List<Map<String, Object>> textModules = new java.util.ArrayList<>();
        textModules.add(textModule("Booking", data.bookingReference()));
        if (data.startTime() != null) {
            textModules.add(textModule("When", WHEN.format(data.startTime())));
        }
        if (data.location() != null) {
            textModules.add(textModule("Where", data.location()));
        }
        if (data.hostName() != null) {
            textModules.add(textModule("Host", data.hostName()));
        }
        textModules.add(textModule("Guests", String.valueOf(data.guests())));
        object.put("textModulesData", textModules);

        object.put("barcode", Map.of("type", "QR_CODE", "value", data.bookingReference()));
        return object;
    }

    private Map<String, Object> localized(String value) {
        return Map.of("defaultValue", Map.of("language", "en", "value", value == null ? "" : value));
    }

    private Map<String, Object> textModule(String header, String body) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("header", header);
        module.put("body", body == null ? "" : body);
        return module;
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
