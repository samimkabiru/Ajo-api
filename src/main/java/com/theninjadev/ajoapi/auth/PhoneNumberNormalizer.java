package com.theninjadev.ajoapi.auth;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PhoneNumberNormalizer {

    private static final Pattern E164 = Pattern.compile("^\\+234[789]\\d{9}$");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^234[789]\\d{9}$");
    private static final Pattern LOCAL = Pattern.compile("^0[789]\\d{9}$");

    public String normalize(String rawInput) {
        if (rawInput == null)
            throw new InvalidPhoneNumberException();

        // Separators people type: spaces, dashes, dots, parentheses — 0803-123-4567, (0803) 123 4567.
        String cleaned = rawInput.replaceAll("[\\s\\-.()]+", "");

        if (E164.matcher(cleaned).matches())
            return cleaned;
        if (COUNTRY_CODE.matcher(cleaned).matches())
            return "+" + cleaned;
        if (LOCAL.matcher(cleaned).matches())
            return "+234" + cleaned.substring(1);

        throw new InvalidPhoneNumberException();
    }
}
