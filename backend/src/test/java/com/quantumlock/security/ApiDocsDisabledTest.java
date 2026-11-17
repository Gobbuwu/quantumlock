package com.quantumlock.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the off switch for the API documentation works: with API_DOCS_ENABLED=false
 * neither the OpenAPI document nor Swagger UI is served, and the API itself still works.
 */
@SpringBootTest(properties = "API_DOCS_ENABLED=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiDocsDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentIsNotServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }

    @Test
    void swaggerUiIsNotServed() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    void healthStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }
}
