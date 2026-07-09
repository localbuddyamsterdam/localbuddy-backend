package com.localbuddy.notification.email;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.util.polling.SyncPoller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(
        name = "app.email.provider",
        havingValue = "azure"
)
public class AzureEmailProviderService implements EmailProviderService {

    private final EmailProperties emailProperties;
    private final AzureCommunicationProperties azureCommunicationProperties;

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

            EmailClient emailClient = new EmailClientBuilder()
                    .connectionString(azureCommunicationProperties.connectionString())
                    .buildClient();

            EmailMessage message = new EmailMessage()
                    .setSenderAddress(emailProperties.fromAddress())
                    .setToRecipients(List.of(new EmailAddress(request.toEmail())))
                    .setSubject(request.subject())
                    .setBodyPlainText(request.message());

            if (request.htmlBody() != null && !request.htmlBody().isBlank()) {
                message.setBodyHtml(request.htmlBody());
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
            return new EmailSendResult(
                    false,
                    null,
                    ex.getMessage()
            );
        }
    }
}