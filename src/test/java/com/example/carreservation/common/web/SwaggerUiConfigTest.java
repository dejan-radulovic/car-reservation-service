package com.example.carreservation.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The page, its version-agnostic webjar assets and the spec it loads are all served. */
@WebMvcTest(SwaggerUiConfig.class)
class SwaggerUiConfigTest {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/swagger-ui", "/swagger-ui/"})
    void shortPathsRedirectToThePage(String path) {
        assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.FOUND).hasRedirectedUrl("/swagger-ui/index.html");
    }

    @Test
    void servesThePageItsAssetsAndTheSpec() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk()
                .bodyText().contains("/openapi/car-reservation-api.yaml");
        assertThat(mvc.get().uri("/webjars/swagger-ui/swagger-ui-bundle.js")).hasStatusOk();
        assertThat(mvc.get().uri("/webjars/swagger-ui/swagger-ui.css")).hasStatusOk();
        assertThat(mvc.get().uri("/openapi/car-reservation-api.yaml")).hasStatusOk()
                .bodyText().startsWith("openapi: 3.0.3");
    }
}
