package com.optionmgr.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the unified error format from docs/api/api-contract.md and the JSON conventions
 * (BigDecimal as string, ISO dates), using a probe controller that exists only in this test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.ProbeController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    record ProbeRequest(
            @NotBlank String symbol,
            @NotNull @Positive BigDecimal price,
            @NotNull LocalDate expiration) {
    }

    @RestController
    static class ProbeController {

        @PostMapping("/api/test-probe/echo")
        ProbeRequest echo(@Valid @RequestBody ProbeRequest request) {
            return request;
        }

        @GetMapping("/api/test-probe/items/{id}")
        Map<String, Object> item(@PathVariable long id) {
            throw ApiException.notFound("Item " + id + " not found");
        }

        @GetMapping("/api/test-probe/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("secret internal detail at com.optionmgr.Something");
        }

        @GetMapping("/api/test-probe/amounts")
        Map<String, Object> amounts() {
            return Map.of(
                    "premium", new BigDecimal("349.35"),
                    "large", new BigDecimal("1E+3"),
                    "expiration", LocalDate.of(2026, 12, 18),
                    "tradedAt", Instant.parse("2026-11-10T14:30:00Z"));
        }
    }

    @Test
    void validationFailureReturnsValidationErrorWithFieldDetails() throws Exception {
        String body = """
                {"symbol": " ", "price": "-1.00", "expiration": null}
                """;

        JsonNode json = assertErrorShape(
                mockMvc.perform(post("/api/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "VALIDATION_ERROR", "/api/test-probe/echo");

        assertThat(json.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(json.get("details")).hasSize(3);
        assertThat(json.get("details").findValuesAsText("field"))
                .containsExactly("expiration", "price", "symbol");
        json.get("details").forEach(d -> assertThat(d.get("message").asText()).isNotBlank());
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        JsonNode json = assertErrorShape(
                mockMvc.perform(post("/api/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content("{not json")),
                400, "BAD_REQUEST", "/api/test-probe/echo");
        assertThat(json.get("details")).isEmpty();
    }

    @Test
    void pathVariableTypeMismatchReturnsBadRequestWithField() throws Exception {
        JsonNode json = assertErrorShape(mockMvc.perform(get("/api/test-probe/items/abc")),
                400, "BAD_REQUEST", "/api/test-probe/items/abc");
        assertThat(json.get("details").get(0).get("field").asText()).isEqualTo("id");
    }

    @Test
    void unknownRouteReturnsNotFound() throws Exception {
        assertErrorShape(mockMvc.perform(get("/api/no-such-endpoint")), 404, "NOT_FOUND", "/api/no-such-endpoint");
    }

    @Test
    void apiExceptionKeepsItsCodeAndMessage() throws Exception {
        JsonNode json = assertErrorShape(mockMvc.perform(get("/api/test-probe/items/42")),
                404, "NOT_FOUND", "/api/test-probe/items/42");
        assertThat(json.get("message").asText()).isEqualTo("Item 42 not found");
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowed() throws Exception {
        assertErrorShape(mockMvc.perform(delete("/api/health")), 405, "METHOD_NOT_ALLOWED", "/api/health");
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        assertErrorShape(
                mockMvc.perform(post("/api/test-probe/echo").contentType(MediaType.TEXT_PLAIN).content("x")),
                415, "UNSUPPORTED_MEDIA_TYPE", "/api/test-probe/echo");
    }

    @Test
    void unexpectedExceptionReturnsInternalErrorWithoutLeakingDetails() throws Exception {
        String raw = mockMvc.perform(get("/api/test-probe/boom"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(raw);
        assertThat(json.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(json.get("message").asText()).isEqualTo("Internal server error");
        assertThat(raw).doesNotContain("secret").doesNotContain("IllegalStateException").doesNotContain("trace");
    }

    @Test
    void bigDecimalIsSerializedAsPlainStringAndDatesAsIso() throws Exception {
        mockMvc.perform(get("/api/test-probe/amounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value("349.35"))
                .andExpect(jsonPath("$.large").value("1000"))
                .andExpect(jsonPath("$.expiration").value("2026-12-18"))
                .andExpect(jsonPath("$.tradedAt").value("2026-11-10T14:30:00Z"));
    }

    @Test
    void bigDecimalAcceptsStringInputAndEchoesStringWithScalePreserved() throws Exception {
        String body = """
                {"symbol": "AAPL", "price": "3.50", "expiration": "2026-12-18"}
                """;
        mockMvc.perform(post("/api/test-probe/echo").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value("3.50"))
                .andExpect(jsonPath("$.expiration").value("2026-12-18"));
    }

    private JsonNode assertErrorShape(ResultActions result, int httpStatus, String code, String path)
            throws Exception {
        String raw = result.andExpect(status().is(httpStatus))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(raw);

        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "status", "path", "timestamp", "details");
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("status").asInt()).isEqualTo(httpStatus);
        assertThat(json.get("path").asText()).isEqualTo(path);
        assertThat(json.get("message").asText()).isNotBlank();
        assertThat(json.get("details").isArray()).isTrue();
        assertThat(Instant.parse(json.get("timestamp").asText())).isNotNull();
        return json;
    }
}
