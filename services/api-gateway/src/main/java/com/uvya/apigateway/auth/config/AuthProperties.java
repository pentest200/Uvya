package com.uvya.apigateway.auth.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uvya.auth")
public class AuthProperties {

    private String refreshTokenPepper;

    private final Jwt jwt = new Jwt();
    private final RefreshCookie refreshCookie = new RefreshCookie();
    private final Password password = new Password();
    private final RateLimit rateLimit = new RateLimit();
    private final Cors cors = new Cors();

    public String getRefreshTokenPepper() { return refreshTokenPepper; }
    public void setRefreshTokenPepper(String refreshTokenPepper) { this.refreshTokenPepper = refreshTokenPepper; }

    public Jwt getJwt() {
        return jwt;
    }

    public RefreshCookie getRefreshCookie() {
        return refreshCookie;
    }

    public Password getPassword() {
        return password;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Jwt {
        private String issuer;
        private Duration accessTokenTtl;
        private Duration refreshTokenTtl;
        private String privateKeyBase64;
        private String publicKeyBase64;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
        public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
        public String getPrivateKeyBase64() { return privateKeyBase64; }
        public void setPrivateKeyBase64(String privateKeyBase64) { this.privateKeyBase64 = privateKeyBase64; }
        public String getPublicKeyBase64() { return publicKeyBase64; }
        public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }
    }

    public static class RefreshCookie {
        private String name;
        private boolean secure;
        private String sameSite;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
        public String getSameSite() { return sameSite; }
        public void setSameSite(String sameSite) { this.sameSite = sameSite; }
    }

    public static class Password {
        private int bcryptStrength;

        public int getBcryptStrength() { return bcryptStrength; }
        public void setBcryptStrength(int bcryptStrength) { this.bcryptStrength = bcryptStrength; }
    }

    public static class RateLimit {
        private int loginIpLimit;
        private int loginIdentityLimit;
        private Duration window;
        private int maxFailedAttempts;
        private Duration lockDuration;

        public int getLoginIpLimit() { return loginIpLimit; }
        public void setLoginIpLimit(int loginIpLimit) { this.loginIpLimit = loginIpLimit; }
        public int getLoginIdentityLimit() { return loginIdentityLimit; }
        public void setLoginIdentityLimit(int loginIdentityLimit) { this.loginIdentityLimit = loginIdentityLimit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public int getMaxFailedAttempts() { return maxFailedAttempts; }
        public void setMaxFailedAttempts(int maxFailedAttempts) { this.maxFailedAttempts = maxFailedAttempts; }
        public Duration getLockDuration() { return lockDuration; }
        public void setLockDuration(Duration lockDuration) { this.lockDuration = lockDuration; }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
