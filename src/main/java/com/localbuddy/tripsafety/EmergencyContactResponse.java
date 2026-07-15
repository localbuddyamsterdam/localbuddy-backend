package com.localbuddy.tripsafety;

public record EmergencyContactResponse(
        String firstName,
        String lastName,
        String email,
        String contactPhone,
        String relationship
) {
    public static EmergencyContactResponse from(EmergencyContact contact) {
        return new EmergencyContactResponse(
                contact.getFirstName(),
                contact.getLastName(),
                contact.getEmail(),
                contact.getContactPhone(),
                contact.getRelationship()
        );
    }
}
