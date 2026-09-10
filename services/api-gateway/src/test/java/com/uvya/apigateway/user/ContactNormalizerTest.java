package com.uvya.apigateway.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.uvya.apigateway.user.domain.ContactIdentifierType;
import com.uvya.apigateway.user.service.ContactNormalizer;
import com.uvya.apigateway.user.service.InvalidContactException;

class ContactNormalizerTest {
    private final ContactNormalizer normalizer = new ContactNormalizer();

    @Test
    void normalizesEmailAndPhoneWithoutRetainingRawValues() {
        String email = normalizer.normalize(ContactIdentifierType.EMAIL, "  Alice@Example.COM ");
        String phone = normalizer.normalize(ContactIdentifierType.PHONE, " +91 (987) 654-3210 ");

        assertThat(email).isEqualTo("alice@example.com");
        assertThat(phone).isEqualTo("+919876543210");
        assertThat(normalizer.hash(email)).hasSize(64).doesNotContain("@");
        assertThat(normalizer.hash(phone)).hasSize(64).doesNotContain("+");
    }

    @Test
    void rejectsMalformedIdentifiers() {
        assertThatThrownBy(() -> normalizer.normalize(ContactIdentifierType.EMAIL, "not-an-email"))
                .isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> normalizer.normalize(ContactIdentifierType.PHONE, "123"))
                .isInstanceOf(InvalidContactException.class);
    }
}
