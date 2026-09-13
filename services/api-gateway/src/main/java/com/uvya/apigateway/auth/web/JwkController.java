package com.uvya.apigateway.auth.web;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uvya.apigateway.auth.config.RsaKeyConfiguration;

@RestController
public class JwkController {
    private final RsaKeyConfiguration keys;

    public JwkController(RsaKeyConfiguration keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> keys() {
        RSAPublicKey publicKey = (RSAPublicKey) keys.publicKey();
        return Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "uvya-auth-key", "use", "sig",
                "alg", "RS256", "n", base64Url(publicKey.getModulus()), "e", base64Url(publicKey.getPublicExponent()))));
    }

    private String base64Url(BigInteger value) {
        byte[] encoded = value.toByteArray();
        int offset = encoded.length > 1 && encoded[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(encoded, offset,
                encoded.length));
    }
}
