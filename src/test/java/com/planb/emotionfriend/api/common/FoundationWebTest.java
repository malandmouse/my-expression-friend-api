package com.planb.emotionfriend.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.planb.emotionfriend.api.common.config.FoundationConfiguration;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.common.validation.SchemaVersion;
import com.planb.emotionfriend.api.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

@WebMvcTest(controllers = FoundationWebTest.ProbeController.class)
@Import({FoundationConfiguration.class, SecurityConfiguration.class, ApiErrorWriter.class,
        TraceIdFilter.class, GlobalExceptionHandler.class, CurrentActor.class, ResourceAccess.class,
        FoundationWebTest.ProbeController.class})
class FoundationWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessCredentialVerifier verifier;
    @MockitoBean ChildAccessChecker childAccess;

    @BeforeEach
    void identities() {
        when(verifier.verify("adult-token")).thenReturn(new ActorPrincipal("adult-1", ActorPrincipal.Kind.ADULT, null, null, null));
        when(verifier.verify("child-token")).thenReturn(new ActorPrincipal("child-actor", ActorPrincipal.Kind.CHILD, "child-1", "device-1", "plan-1"));
        when(verifier.verify("expired-token")).thenThrow(new ApiException(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    void missingBearerReturnsSafeJsonAndServerTrace() throws Exception {
        var response = mvc.perform(get("/api/v1/probe").header("X-Trace-Id", "untrusted-input"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.details").isMap()).andReturn().getResponse();
        assertThat(response.getHeader("X-Trace-Id")).isNotEqualTo("untrusted-input");
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Trace-Id"));
        assertThat(MDC.get("traceId")).isNull();
        assertThat(response.getCookie("JSESSIONID")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer ", "Bearer a b", "Bearer not-valid-token"})
    void invalidCredentialIsRejected(String header) throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", header))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void duplicateAuthorizationHeadersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer adult-token", "Bearer child-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredCredentialHasDistinctCode() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void identityIsVerifiedPerRequestAndSuccessHasNoEnvelope() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value("adult-1"))
                .andExpect(jsonPath("$.success").doesNotExist());
        mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicLoginPermissionIsLimitedToPost() throws Exception {
        mvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void childCannotCallAdultMethodOrAnotherChildResource() throws Exception {
        mvc.perform(get("/api/v1/probe/adult").header("Authorization", "Bearer child-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/probe/children/child-2").header("Authorization", "Bearer child-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentPermissionsAreNotInferredFromAdultRole() throws Exception {
        doThrow(new ApiException(ErrorCode.CONSENT_REQUIRED)).when(childAccess).check(any(), any(), any());
        mvc.perform(get("/api/v1/probe/children/child-1").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"ok\",\"slot\":1,\"unexpected\":true}",
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"ok\",\"slot\":\"1\"}",
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"ok\",\"slot\":1.5}",
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"ok\",\"slot\":1,\"slot\":2}",
            "{\"schemaVersion\":\"0.2.0\",\"text\":\"ok\",\"slot\":1}",
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"\",\"slot\":6}",
            "{\"schemaVersion\":\"0.3.0\",\"text\":null}", "{",
            "{\"schemaVersion\":\"0.3.0\",\"text\":\"ok\",\"slot\":1} {}"})
    void invalidJsonAndDtoConstraintsHaveSafe422Response(String body) throws Exception {
        mvc.perform(post("/api/v1/probe").header("Authorization", "Bearer adult-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_SCHEMA"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void validDtoIsReturnedDirectly() throws Exception {
        mvc.perform(post("/api/v1/probe").header("Authorization", "Bearer adult-token").contentType("application/json")
                        .content("{\"schemaVersion\":\"0.3.0\",\"text\":\"synthetic\",\"slot\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("synthetic"));
    }

    @Test
    void unknownRouteAndUnsupportedMethodKeepHttpSemantics() throws Exception {
        mvc.perform(get("/api/v1/missing").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/v1/probe/adult").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"));
    }

    @Test
    void recoveryErrorIncludesRequiredNullAndNoAwardMutation() throws Exception {
        mvc.perform(get("/api/v1/probe/recovery").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKPOINT_EXPIRED"))
                .andExpect(jsonPath("$.details.recovery.status").value("CHECKPOINT_EXPIRED"))
                .andExpect(jsonPath("$.details.recovery.nextAction").value("RETURN_HOME"))
                .andExpect(jsonPath("$.details.recovery.viewPattern").value("U13"))
                .andExpect(jsonPath("$.details.recovery.localDraftRestorable").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.details.recovery.regularAwardMutation").value(false));
    }

    @Test
    void unexpectedFailureDoesNotLeakExceptionOrSubmittedValues() throws Exception {
        var result = mvc.perform(get("/api/v1/probe/failure").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("synthetic-secret", "IllegalStateException", "stackTrace");
    }

    @Test
    void documentationPermissionDoesNotPermitWritesOrUnrelatedPaths() throws Exception {
        mvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui-unrelated")).andExpect(status().isUnauthorized());
    }

    @Test
    void internalApiHasNoClientAccess() throws Exception {
        mvc.perform(get("/internal/v1/jobs/synthetic").header("Authorization", "Bearer adult-token"))
                .andExpect(status().isForbidden());
    }

    record Input(@SchemaVersion String schemaVersion, @NotBlank @Size(max = 40) String text,
                 @NotNull @Min(1) @Max(5) Integer slot) {}

    @RestController
    static class ProbeController {
        private final CurrentActor actors;
        private final ResourceAccess access;
        ProbeController(CurrentActor actors, ResourceAccess access) { this.actors = actors; this.access = access; }
        @GetMapping("/api/v1/probe") Map<String, String> actor() { return Map.of("actor", actors.require().actorId()); }
        @PostMapping("/api/v1/probe") Input echo(@Valid @RequestBody Input input) { return input; }
        @PostMapping("/api/v1/auth/login") Map<String, Boolean> login() { return Map.of("public", true); }
        @PreAuthorize("hasAuthority('CREDENTIAL_ADULT')")
        @GetMapping("/api/v1/probe/adult") Map<String, Boolean> adult() { return Map.of("adult", true); }
        @GetMapping("/api/v1/probe/children/{child}") Map<String, Boolean> child(@PathVariable String child) {
            access.require(actors.require(), child, ChildAccessChecker.Capability.VIEW_PROFILE);
            return Map.of("allowed", true);
        }
        @GetMapping("/api/v1/probe/recovery") void recovery() { throw new ApiException(ErrorCode.CHECKPOINT_EXPIRED); }
        @GetMapping("/api/v1/probe/failure") void failure() { throw new IllegalStateException("synthetic-secret"); }
    }
}
