package com.uvya.apigateway.auth.config;

import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.uvya.apigateway.auth.repository.AuthSessionRepository;
import com.uvya.apigateway.auth.web.SessionRevocationFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    SessionRevocationFilter sessionRevocationFilter(AuthSessionRepository sessionRepository) {
        return new SessionRevocationFilter(sessionRepository);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            CorsConfigurationSource corsConfigurationSource,
            SessionRevocationFilter sessionRevocationFilter) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/v1/auth/register", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/auth/login", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/auth/devices", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/users/me", HttpMethod.PATCH.name()),
                                new AntPathRequestMatcher("/v1/users/contacts", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/users/*/block", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/users/*/block", HttpMethod.DELETE.name()),
                                new AntPathRequestMatcher("/v1/chats", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/chats/*", HttpMethod.PATCH.name()),
                                new AntPathRequestMatcher("/v1/chats/*/members", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/chats/*/members/*", HttpMethod.DELETE.name()),
                                new AntPathRequestMatcher("/v1/chats/*/leave", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/chats/*/messages", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/v1/chats/*/messages/*", HttpMethod.PATCH.name()),
                                new AntPathRequestMatcher("/v1/chats/*/messages/*", HttpMethod.DELETE.name())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health/**", "/actuator/health/**", "/v1/auth/csrf",
                                "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh",
                                "/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/devices").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/auth/sessions").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/v1/auth/sessions/*").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                .addFilterAfter(sessionRevocationFilter, BearerTokenAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Request-ID", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(Arrays.asList("X-Request-ID"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        return new JwtAuthenticationConverter();
    }

    @Bean
    JwtEncoder jwtEncoder(RsaKeyConfiguration keys) {
        return new NimbusJwtEncoder(keys.jwkSource());
    }

    @Bean
    JwtDecoder jwtDecoder(RsaKeyConfiguration keys, AuthProperties properties) {
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.publicKey())
                        .build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(properties.getJwt().getIssuer()));
        return decoder;
    }

    @Bean
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(
                properties.getPassword().getBcryptStrength());
    }
}
