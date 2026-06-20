package com.localbuddy.tripsafety;

public record EmergencyContactResponse(
        String contactName,
        String contactPhone,
        String relationship
) {
    public static EmergencyContactResponse from(EmergencyContact contact) {
        return new EmergencyContactResponse(
                contact.getContactName(),
                contact.getContactPhone(),
                contact.getRelationship()
        );
    }
}
