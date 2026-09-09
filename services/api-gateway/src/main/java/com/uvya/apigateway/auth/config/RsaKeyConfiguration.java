package com.uvya.apigateway.auth.config;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
public class RsaKeyConfiguration {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public RsaKeyConfiguration(AuthProperties properties) {
        String privateKeyValue = properties.getJwt().getPrivateKeyBase64();
        String publicKeyValue = properties.getJwt().getPublicKeyBase64();
        if (privateKeyValue == null || privateKeyValue.isBlank()
                || publicKeyValue == null || publicKeyValue.isBlank()) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                privateKey = pair.getPrivate();
                publicKey = pair.getPublic();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to generate development JWT keys", exception);
            }
        } else {
            try {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privateKeyValue)));
                publicKey = factory.generatePublic(new X509EncodedKeySpec(decode(publicKeyValue)));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to load configured JWT keys", exception);
            }
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) publicKey)
                .privateKey(privateKey)
                .keyID("uvya-auth-key")
                .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    public PrivateKey privateKey() { return privateKey; }
    public PublicKey publicKey() { return publicKey; }

    private static byte[] decode(String value) {
        String normalized = value.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized.getBytes(StandardCharsets.US_ASCII));
    }
}
