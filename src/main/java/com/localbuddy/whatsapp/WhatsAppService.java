package com.localbuddy.whatsapp;

import com.localbuddy.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp integration with two modes:
 * <ul>
 *   <li><b>Click-to-chat links</b> (wa.me) — always available, no credentials, no cost.</li>
 *   <li><b>Business API</b> (Meta Cloud API) — config-guarded outbound messages; dormant
 *       until {@code app.whatsapp.access-token} and {@code phone-number-id} are set.</li>
 * </ul>
 *
 * <p>Business-initiated messages (a booking confirmation to someone who never messaged us)
 * only deliver as Meta-approved <b>templates</b> — free-form text delivers solely inside an
 * open 24-hour customer-service session. Utility templates are the anti-spam guarantee too:
 * without an approved marketing template, this integration physically cannot send ads.
 */
@Service
public class WhatsAppService {

    private final String accessToken;
    private final String phoneNumberId;
    private final String apiBaseUrl;
    private final String templateLanguage;
    // Short connect/read timeouts so an unresponsive graph.facebook.com fails fast on the
    // notification-processor thread instead of hanging it (same pattern as the token verifiers).
    private final RestClient restClient;

    public WhatsAppService(
            @Value("${app.whatsapp.access-token:}") String accessToken,
            @Value("${app.whatsapp.phone-number-id:}") String phoneNumberId,
            @Value("${app.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String apiBaseUrl,
            @Value("${app.whatsapp.template-language:en}") String templateLanguage) {
        this.accessToken = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.apiBaseUrl = apiBaseUrl;
        this.templateLanguage = templateLanguage;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
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

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", digits,
                "type", "text",
                "text", Map.of("preview_url", false, "body", message)
        );
        return post(body);
    }

    /**
     * Sends a Meta-approved template message (the only kind that delivers business-initiated,
     * outside an open 24h session). {@code bodyParams} fill the template's <code>{{1}}…{{n}}</code>
     * body placeholders in order; the language is {@code app.whatsapp.template-language}.
     */
    public WhatsAppSendResult sendTemplate(String toPhone, String templateName, List<String> bodyParams) {
        return sendTemplate(toPhone, templateName, bodyParams, null);
    }

    /**
     * Same as above, plus {@code buttonUrlParams}: dynamic suffixes for the template's URL buttons,
     * in button-index order (index 0 first, etc.) — e.g. a booking reference for a "Manage my
     * booking" button followed by an encoded map query for an "Open in Maps" button, each appended
     * to that button's static base URL configured on the template itself in Meta. Stops at the
     * first null/blank entry (button indices must stay contiguous from 0); null/empty list omits
     * button components entirely (the template must then have no URL buttons, or Meta rejects it).
     */
    public WhatsAppSendResult sendTemplate(
            String toPhone, String templateName, List<String> bodyParams, List<String> buttonUrlParams) {
        if (!isConfigured()) {
            throw new BadRequestException(
                    "WhatsApp Business API is not configured. Use a click-to-chat link instead.");
        }
        String digits = normalizePhone(toPhone);
        if (digits.isEmpty()) {
            throw new BadRequestException("A valid recipient phone number is required");
        }
        if (templateName == null || templateName.isBlank()) {
            throw new BadRequestException("Template name is required");
        }

        List<Map<String, Object>> components = new ArrayList<>();
        if (bodyParams != null && !bodyParams.isEmpty()) {
            List<Map<String, Object>> parameters = bodyParams.stream()
                    .map(p -> Map.<String, Object>of("type", "text", "text", p == null ? "" : p))
                    .toList();
            components.add(Map.of("type", "body", "parameters", parameters));
        }
        if (buttonUrlParams != null) {
            for (int i = 0; i < buttonUrlParams.size(); i++) {
                String param = buttonUrlParams.get(i);
                if (param == null || param.isBlank()) {
                    break;
                }
                components.add(Map.of(
                        "type", "button",
                        "sub_type", "url",
                        "index", String.valueOf(i),
                        "parameters", List.of(Map.of("type", "text", "text", param))
                ));
            }
        }

        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("name", templateName.trim());
        template.put("language", Map.of("code", templateLanguage));
        if (!components.isEmpty()) {
            template.put("components", components);
        }

        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", digits,
                "type", "template",
                "template", template
        );
        return post(body);
    }

    private WhatsAppSendResult post(Map<String, Object> body) {
        try {
            Map<?, ?> response = restClient
                    .post()
                    .uri(apiBaseUrl + "/" + phoneNumberId + "/messages")
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            String messageId = extractMessageId(response);
            return new WhatsAppSendResult(messageId, "sent");
        } catch (RestClientResponseException ex) {
            // Meta's actual error (code/message/type) lives in the response body, not the
            // exception message — surface it so a failure reason is actually diagnosable.
            throw new BadRequestException("Unable to send WhatsApp message: "
                    + ex.getStatusCode() + " " + truncate(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            throw new BadRequestException("Unable to send WhatsApp message: " + ex.getMessage());
        }
    }

    private String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "[no body]";
        }
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
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
