package io.rapha.spring.reactive.security;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public class SecuredRestApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebTestClient rest;

    @Test
    public void messageWhenNotAuthenticated() {
        this.rest
                .get()
                .uri("/api/admin")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}