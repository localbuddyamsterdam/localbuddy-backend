package com.localbuddy.whatsapp;

public record WhatsAppSendResult(
        String providerMessageId,
        String status
) {
}
