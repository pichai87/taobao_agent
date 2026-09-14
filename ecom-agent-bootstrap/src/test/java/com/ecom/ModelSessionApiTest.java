package com.ecom;

import com.ecom.domain.ModelRuntime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ModelSessionApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Sessions sessions;
    String settings(boolean consent) throws Exception {return json.writeValueAsString(new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-only-not-a-real-key",consent));}
    @Test void authenticationAndCsrfProtectSecrets() throws Exception {
        mvc.perform(get("/api/model-session")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/model-session").with(user("alice").roles("ANALYST")).contentType("application/json").content(settings(true)))
            .andExpect(status().isForbidden());
    }
    @Test void configIsRedactedSessionScopedAndCanBeCleared() throws Exception {
        var browserSession=new MockHttpSession();
        var response=mvc.perform(post("/api/model-session").session(browserSession).with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(settings(true))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("apiKey","sk-test-only");
        String id=json.readTree(response).path("id").asText();
        assertThatThrownBy(()->sessions.require("bob",id)).hasMessage("MODEL_SESSION_EXPIRED");
        mvc.perform(get("/api/model-session").with(user("alice").roles("ANALYST"))).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get("/api/model-session").session(browserSession).with(user("alice").roles("ANALYST"))).andExpect(jsonPath("$.configured").value(true));
        mvc.perform(post("/api/model-session/clear").session(browserSession).with(user("alice").roles("ANALYST")).with(csrf())).andExpect(status().isOk());
        assertThatThrownBy(()->sessions.require("alice",id)).hasMessage("MODEL_SESSION_EXPIRED");
    }
    @Test void consentAndSecureTransportAreRequired() throws Exception {
        mvc.perform(post("/api/model-session").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(settings(false))).andExpect(jsonPath("$.code").value("MODEL_CONSENT_REQUIRED"));
        mvc.perform(post("/api/model-session").with(user("alice").roles("ANALYST")).with(csrf())
            .with(request->{request.setRemoteAddr("192.168.1.8");return request;}).contentType("application/json").content(settings(true)))
            .andExpect(jsonPath("$.code").value("MODEL_HTTPS_REQUIRED"));
    }
}
