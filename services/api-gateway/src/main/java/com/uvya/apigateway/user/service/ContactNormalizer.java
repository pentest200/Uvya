package com.uvya.apigateway.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.uvya.apigateway.user.domain.ContactIdentifierType;

@Component
public class ContactNormalizer {
    public String normalize(ContactIdentifierType type, String value) {
        if (type == null || value == null) {
            throw new InvalidContactException();
        }
        String normalized = value.trim();
        if (type == ContactIdentifierType.EMAIL) {
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new InvalidContactException();
            }
        } else {
            normalized = normalized.replaceAll("[\\s().-]", "");
            if (normalized.startsWith("+")) {
                normalized = "+" + normalized.substring(1).replace("+", "");
            }
            if (!normalized.matches("^\\+?[0-9]{7,15}$")) {
                throw new InvalidContactException();
            }
            if (!normalized.startsWith("+")) {
                normalized = "+" + normalized;
            }
        }
        return normalized;
    }

    public String hash(String normalizedValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
