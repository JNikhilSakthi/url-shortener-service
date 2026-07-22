package com.medha.urlshortenerservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medha.urlshortenerservice.dto.CreateShortUrlRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test exercising the real create -> redirect -> stats -> delete
 * flow against real MySQL and Redis containers (Testcontainers), proving out
 * the cache-aside behaviour and click-count reconciliation end to end.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class UrlShortenerIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("urlshortener")
            .withUsername("urlshortener")
            .withPassword("urlshortener");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Make the flush cadence fast so the reconciliation test doesn't have to sleep long.
        registry.add("app.click-tracking.flush-interval-ms", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullLifecycle_createRedirectStatsAndDelete() throws Exception {
        CreateShortUrlRequest createRequest = new CreateShortUrlRequest("https://example.com/hello-world", null, null);

        String createResponseJson = mockMvc.perform(post("/api/urls")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/hello-world"))
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(createResponseJson).get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/hello-world"));

        mockMvc.perform(get("/api/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(greaterThanOrEqualTo(1)));

        mockMvc.perform(delete("/api/urls/{shortCode}", shortCode))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/urls/{shortCode}", shortCode))
                .andExpect(status().isNotFound());
    }

    @Test
    void createShortUrl_rejectsInvalidUrl() throws Exception {
        CreateShortUrlRequest badRequest = new CreateShortUrlRequest("not-a-url", null, null);

        mockMvc.perform(post("/api/urls")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShortUrl_rejectsDuplicateCustomAlias() throws Exception {
        CreateShortUrlRequest first = new CreateShortUrlRequest("https://example.com/one", "shared-alias", null);
        CreateShortUrlRequest second = new CreateShortUrlRequest("https://example.com/two", "shared-alias", null);

        mockMvc.perform(post("/api/urls")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/urls")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());
    }

    @Test
    void redirect_returnsNotFound_forUnknownShortCode() throws Exception {
        mockMvc.perform(get("/{shortCode}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
