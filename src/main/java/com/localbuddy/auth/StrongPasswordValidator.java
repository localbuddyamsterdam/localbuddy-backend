package com.localbuddy.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank is the responsibility of @NotBlank.
        if (value == null) {
            return true;
        }

        if (value.length() < 8 || value.length() > 100) {
            return false;
        }

        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;

        for (char c : value.toCharArray()) {
            if (Character.isWhitespace(c)) {
                return false;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                special = true;
            }
        }

        return upper && lower && digit && special;
    }
}
