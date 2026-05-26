package io.rapha.spring.reactive.security;


import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.rapha.spring.reactive.security.auth.jwt.JWTSecrets;
import io.rapha.spring.reactive.security.auth.jwt.JWTTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SecuredRestApplicationTest {

    @Autowired
    private WebTestClient rest;

    @Test
    public void apiRequestWithoutTokenIsUnauthorized() {
        this.rest
                .get()
                .uri("/api/admin")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    public void basicLoginIssuesJwt() {
        String authorization = login();
        assertNotNull(authorization, "login should return an Authorization header");
        assertTrue(authorization.startsWith("Bearer "), "login should issue a Bearer token");
    }

    @Test
    public void validTokenGrantsAccessToPermittedRole() {
        // the demo user holds ROLE_ADMIN
        this.rest
                .get()
                .uri("/api/admin")
                .header(HttpHeaders.AUTHORIZATION, login())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].message").isEqualTo("Hello Admin!");
    }

    @Test
    public void validTokenIsForbiddenWhenRoleIsMissing() {
        // the demo user lacks ROLE_GUEST, so method security must reject the request
        this.rest
                .get()
                .uri("/api/guest")
                .header(HttpHeaders.AUTHORIZATION, login())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    public void malformedTokenIsUnauthorized() {
        this.rest
                .get()
                .uri("/api/admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    public void expiredTokenIsUnauthorized() {
        String expired = signedToken(JWTTokenService.ISSUER, Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        this.rest
                .get()
                .uri("/api/admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    public void tokenFromUntrustedIssuerIsUnauthorized() {
        String foreign = signedToken("attacker.example", Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        this.rest
                .get()
                .uri("/api/admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Logs in with HTTP Basic and returns the issued {@code "Bearer ..."} Authorization header.
     */
    private String login() {
        return this.rest
                .get()
                .uri("/login")
                .headers(headers -> headers.setBasicAuth("user", "user"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);
    }

    /**
     * Builds a JWT signed with the demo secret, used to exercise the verifier's
     * negative paths (expired / untrusted issuer).
     */
    private static String signedToken(String issuer, Date expiration) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("user")
                    .issuer(issuer)
                    .claim("roles", "ROLE_USER,ROLE_ADMIN")
                    .expirationTime(expiration)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(JWTSecrets.DEFAULT_SECRET));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build test JWT", e);
        }
    }
}
