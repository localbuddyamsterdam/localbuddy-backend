package com.localbuddy.auth;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces rigid password rules: 8-100 chars, no whitespace, and at least one
 * uppercase letter, one lowercase letter, one digit, and one special character.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "Password must be 8-100 characters with no spaces and include an uppercase letter, a lowercase letter, a digit, and a special character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
