package com.localbuddy.whatsapp;

import com.localbuddy.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WhatsApp integration with two modes:
 * <ul>
 *   <li><b>Click-to-chat links</b> (wa.me) — always available, no credentials, no cost.</li>
 *   <li><b>Business API</b> (Meta Cloud API) — config-guarded outbound messages; dormant
 *       until {@code app.whatsapp.access-token} and {@code phone-number-id} are set.</li>
 * </ul>
 */
@Service
public class WhatsAppService {

    private final String accessToken;
    private final String phoneNumberId;
    private final String apiBaseUrl;

    public WhatsAppService(
            @Value("${app.whatsapp.access-token:}") String accessToken,
            @Value("${app.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${app.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String apiBaseUrl) {
        this.accessToken = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.apiBaseUrl = apiBaseUrl;
    }

    /** Whether the Business API (outbound sending) is configured. */
    public boolean isConfigured() {
        return notBlank(accessToken) && notBlank(phoneNumberId);
    }

    /** Builds a wa.me click-to-chat link with an optional prefilled message. */
    public String buildClickToChatLink(String phone, String message) {
        String digits = normalizePhone(phone);
        if (digits.isEmpty()) {
            throw new BadRequestException("A valid phone number is required");
        }
        String link = "https://wa.me/" + digits;
        if (message != null && !message.isBlank()) {
            link += "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        }
        return link;
    }

    /** Sends a text message via the Meta Cloud API. Requires the Business API to be configured. */
    public WhatsAppSendResult sendMessage(String toPhone, String message) {
        if (!isConfigured()) {
            throw new BadRequestException(
                    "WhatsApp Business API is not configured. Use a click-to-chat link instead.");
        }
        String digits = normalizePhone(toPhone);
        if (digits.isEmpty()) {
            throw new BadRequestException("A valid recipient phone number is required");
        }
        if (message == null || message.isBlank()) {
            throw new BadRequestException("Message body is required");
        }

        try {
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", digits,
                    "type", "text",
                    "text", Map.of("preview_url", false, "body", message)
            );

            Map<?, ?> response = RestClient.create()
                    .post()
                    .uri(apiBaseUrl + "/" + phoneNumberId + "/messages")
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            String messageId = extractMessageId(response);
            return new WhatsAppSendResult(messageId, "sent");
        } catch (Exception ex) {
            throw new BadRequestException("Unable to send WhatsApp message: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractMessageId(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object messages = response.get("messages");
        if (messages instanceof java.util.List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object id = ((Map<String, Object>) first).get("id");
            return id != null ? id.toString() : null;
        }
        return null;
    }

    /** Strips everything but digits (drops a leading + as well) for wa.me / Cloud API. */
    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("[^0-9]", "");
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
