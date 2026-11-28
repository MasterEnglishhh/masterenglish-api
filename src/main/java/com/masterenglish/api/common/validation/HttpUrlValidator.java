package com.masterenglish.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return isValid(value);
    }

    public static boolean isValid(CharSequence value) {
        if (value == null) {
            return true;
        }
        String str = value.toString().strip();
        if (str.isEmpty()) {
            return true;
        }
        try {
            URI uri = new URI(str);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
