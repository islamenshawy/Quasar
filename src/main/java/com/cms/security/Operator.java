package com.cms.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller parameter: the username of the signed-in operator (or the API client, e.g. DEXXIS).
 * Replaces the test-only X-Operator header; the value always comes from the authenticated principal.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Operator {
}
