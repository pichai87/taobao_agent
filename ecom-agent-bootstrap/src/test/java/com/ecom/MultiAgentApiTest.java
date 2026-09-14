package com.ecom;

import com.ecom.agent.collaboration.AgentContracts.Goal;
import com.ecom.domain.*;
import com.ecom.domain.ModelRuntime.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class MultiAgentApiTest extends AgentTestFixture {
    @Autowired MockMvc mvc;
    @MockitoBean Gateway gateway;
    Map<String,Object> body(String session,String mode) {
        var map=new LinkedHashMap<String,Object>();map.put("question","GMV 为什么下降");map.put("date","2026-09-12");map.put("metric","GMV");map.put("reviewRequired",true);
        if(session!=null) map.put("modelSessionId",session);map.put("executionMode",mode);return map;
    }
    @Test void modeIsValidatedAndRequiresAnOwnedSession() throws Exception {
        for(String mode:List.of("invented",ModelRuntime.MULTI_MODE)) {
            mvc.perform(post("/api/runs").with(user("mode-check").roles("ANALYST")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.writeValueAsString(body(null,mode)))).andExpect(status().isBadRequest());
        }
        var session=sessions.create("other-owner",new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-only-not-a-real-key",true));
        mvc.perform(post("/api/runs").with(user("mode-check").roles("ANALYST")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.writeValueAsString(body(session.id(),ModelRuntime.MULTI_MODE))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MODEL_SESSION_EXPIRED"));
    }
    @Test void approvedMultiModeActuallyDispatchesToExpertsAndCannotResume() throws Exception {
        String owner="api-multi";var session=sessions.create(owner,new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-only-not-a-real-key",true));
        FixtureModel model=new FixtureModel(Goal.ATTRIBUTION);
        when(gateway.complete(anyString(),anyString(),anyList(),anyList())).thenAnswer(inv->model.complete(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3)));
        String response=mvc.perform(post("/api/runs").with(user(owner).roles("ANALYST")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(json.writeValueAsString(body(session.id(),ModelRuntime.MULTI_MODE))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_FOR_REVIEW")).andExpect(jsonPath("$.mode").value(ModelRuntime.MULTI_MODE))
            .andExpect(jsonPath("$.request.compareDate").value("2026-09-11")).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("id").asText();verifyNoInteractions(gateway);
        mvc.perform(post("/api/runs/"+id+"/approve").with(user(owner).roles("ANALYST")).with(csrf())).andExpect(status().isOk());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->assertThat(store.find(id,owner).orElseThrow().status()).isEqualTo(com.ecom.domain.Analysis.Status.SUCCEEDED));
        assertThat(model.observations).anyMatch(o->o.actor().equals("analysis_agent")).anyMatch(o->o.actor().equals("query_agent"));
        mvc.perform(post("/api/runs/"+id+"/resume").with(user(owner).roles("ANALYST")).with(csrf())).andExpect(jsonPath("$.code").value("MODEL_RUN_NOT_RESUMABLE"));
        assertThat(response).doesNotContain("sk-test-only");
    }
}
