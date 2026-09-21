package dev.skillsgateway.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.reqstool.annotations.SVCs;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * What a refused request actually puts on the wire.
 *
 * <p>The reference page promises RFC 7807 for every error, but most refusals in this codebase are
 * {@code ResponseStatusException}, and Boot renders those as its own
 * {@code {timestamp,status,error,path}} body — <em>with the reason dropped</em> — unless problem
 * details are switched on. Nothing asserted on an error body before this class, which is why the
 * gap survived: the document said one thing, ~35 call sites did another, and no test read either.
 *
 * <p>So these cases are about the envelope, not about any one endpoint's rules. Each picks a
 * refusal raised by a different mechanism, because the whole point is that they agree.
 */
class ErrorContractTests extends AbstractGatewayTest {

    /**
     * A {@code ResponseStatusException} carries its reason to the client.
     *
     * <p>This is the case the contract was wrong about. The reason text is the only part of the
     * refusal that says <em>why</em>, and the portal reads exactly this field
     * ({@code client.ts} falls back to the bare status line when it is absent), so a dropped
     * reason is a dialog that says "409 Conflict" and nothing else.
     */
    @Test
    @SVCs({"SVC_GW_API_0007"})
    void a_response_status_exception_is_a_problem_document_carrying_its_reason() throws Exception {
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"ftp://example.invalid/repo.git\"}"
                                .formatted(uniqueName("scheme"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("scheme")));
    }

    /** An {@code @ExceptionHandler} refusal has the same envelope, so a client has one shape to read. */
    @Test
    @SVCs({"SVC_GW_API_0007"})
    void an_exception_handler_refusal_uses_the_same_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/snapshots/{id}/vetting", 999_999_999L).with(oidcLogin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * A validation refusal too. Boot renders these through its own handler, so it is the third
     * distinct route to an error body and the one most likely to drift from the other two.
     */
    @Test
    @SVCs({"SVC_GW_API_0007"})
    void a_malformed_body_is_a_problem_document_as_well() throws Exception {
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * The body never carries Boot's own error keys. Asserted rather than assumed: the two shapes
     * differ by which fields exist, so a regression that switched problem details back off would
     * otherwise only show up as a missing {@code detail} somewhere else.
     */
    @Test
    @SVCs({"SVC_GW_API_0007"})
    void the_error_body_is_not_boots_default_shape() throws Exception {
        mockMvc.perform(post("/api/v1/marketplaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"url\":\"ftp://example.invalid/repo.git\"}"
                                .formatted(uniqueName("shape"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }
}
