package com.localbuddy.notification.email;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailAttachment;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.SyncPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(
        name = "app.email.provider",
        havingValue = "azure"
)
public class AzureEmailProviderService implements EmailProviderService {

    private static final Logger log = LoggerFactory.getLogger(AzureEmailProviderService.class);

    private final EmailProperties emailProperties;
    private final AzureCommunicationProperties azureCommunicationProperties;
    // Built once on first send and reused — the ACS client (HTTP pipeline, auth, serializers) is
    // expensive to construct, and was previously rebuilt for every message on the notification thread.
    private volatile EmailClient emailClient;

    public AzureEmailProviderService(EmailProperties emailProperties,
                                     AzureCommunicationProperties azureCommunicationProperties) {
        this.emailProperties = emailProperties;
        this.azureCommunicationProperties = azureCommunicationProperties;
    }

    @Override
    public EmailSendResult sendEmail(EmailSendRequest request) {
        try {
            if (azureCommunicationProperties.connectionString() == null ||
                    azureCommunicationProperties.connectionString().trim().isEmpty()) {
                return new EmailSendResult(
                        false,
                        null,
                        "Azure Communication Services connection string is missing"
                );
            }

            EmailClient emailClient = emailClient();

            EmailMessage message = new EmailMessage()
                    .setSenderAddress(emailProperties.fromAddress())
                    .setToRecipients(List.of(new EmailAddress(request.toEmail())))
                    .setSubject(request.subject())
                    .setBodyPlainText(request.message());

            if (request.htmlBody() != null && !request.htmlBody().isBlank()) {
                message.setBodyHtml(request.htmlBody());
            }

            if (request.icsContent() != null && !request.icsContent().isBlank()) {
                message.setAttachments(List.of(new EmailAttachment(
                        "invite.ics",
                        "text/calendar; method=REQUEST; charset=utf-8",
                        BinaryData.fromString(request.icsContent()))));
            }

            SyncPoller<com.azure.communication.email.models.EmailSendResult,
                    com.azure.communication.email.models.EmailSendResult> poller =
                    emailClient.beginSend(message);

            com.azure.communication.email.models.EmailSendResult result =
                    poller.waitForCompletion().getValue();

            return new EmailSendResult(
                    true,
                    result.getId(),
                    null
            );

        } catch (Exception ex) {
            log.warn("ACS email send failed for {}: {}", request.toEmail(), ex.toString(), ex);
            return new EmailSendResult(
                    false,
                    null,
                    ex.getMessage()
            );
        }
    }

    /** Lazily build the ACS client once (after the connection-string guard) and reuse it thereafter. */
    private EmailClient emailClient() {
        EmailClient client = this.emailClient;
        if (client == null) {
            synchronized (this) {
                client = this.emailClient;
                if (client == null) {
                    client = new EmailClientBuilder()
                            .connectionString(azureCommunicationProperties.connectionString())
                            .buildClient();
                    this.emailClient = client;
                }
            }
        }
        return client;
    }
}