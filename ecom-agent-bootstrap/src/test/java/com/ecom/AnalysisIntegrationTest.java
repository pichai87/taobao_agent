package com.ecom;

import com.ecom.domain.*;
import com.ecom.domain.Ports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.time.Duration;
import java.util.UUID;
import static com.ecom.domain.Analysis.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisIntegrationTest {
    @Autowired RunUseCases runs;
    @Autowired RunStore store;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Request request(String question,boolean review) {
        return new Request(question,LocalDate.of(2026,9,12),LocalDate.of(2026,9,11),"GMV",review);
    }
    Run submit(String question) {return runs.submit("alice",UUID.randomUUID().toString(),request(question,false));}
    Run completed(Run run) {
        await().atMost(Duration.ofSeconds(20)).until(()->runs.get(run.owner(),run.id()).status()!=Status.RUNNING);
        return runs.get(run.owner(),run.id());
    }
    @Test void fullGraphUsesRealDatabaseAndPersistsTrace() {
        Run run=completed(submit("GMV 为什么下降"));
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.report().data().current().gmv()).isEqualByComparingTo("2600");
        assertThat(run.report().data().previous().gmv()).isEqualByComparingTo("3000");
        assertThat(run.report().data().changeRate()).isEqualByComparingTo("-0.133333");
        assertThat(run.report().data().contributions().getFirst().dimension()).isEqualTo("appliances");
        assertThat(run.report().data().contributions().getFirst().delta()).isEqualByComparingTo("-400");
        assertThat(run.report().evidence()).hasSize(4);
        assertThat(store.snapshot(run.id()).completed).isEqualTo(6);
        assertThat(runs.events("alice",run.id(),0).stream().filter(e->e.status().equals("SUCCEEDED"))).hasSize(6);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,run.id())).isEqualTo(2);
    }
    @Test void repeatedRequestIsIdempotent() {
        String key=UUID.randomUUID().toString();
        Run a=runs.submit("alice",key,request("GMV",true));
        Run b=runs.submit("alice",key,request("GMV",true));
        assertThat(a.id()).isEqualTo(b.id());
        assertThatThrownBy(()->runs.submit("alice",key,request("GMV 对比",true)))
            .isInstanceOf(BusinessException.class).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void approvalExecutesAndCannotApproveTwice() {
        Run a=runs.submit("alice",UUID.randomUUID().toString(),request("GMV",true));
        assertThat(a.status()).isEqualTo(Status.WAITING_FOR_REVIEW);
        assertThat(runs.events("alice",a.id(),0)).isEmpty();
        assertThat(completed(runs.approve("alice",a.id())).status()).isEqualTo(Status.SUCCEEDED);
        assertThatThrownBy(()->runs.approve("alice",a.id())).hasMessage("INVALID_RUN_STATE");
    }
    @Test void cancelledReviewNeverQueries() {
        Run a=runs.submit("alice",UUID.randomUUID().toString(),request("GMV",true));
        assertThat(runs.cancel("alice",a.id()).status()).isEqualTo(Status.CANCELLED);
        assertThatThrownBy(()->runs.approve("alice",a.id())).hasMessage("INVALID_RUN_STATE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,a.id())).isZero();
    }
    @Test void ownerIsolationAlsoCoversEventsAndActions() throws Exception {
        Run a=runs.submit("alice",UUID.randomUUID().toString(),request("GMV",true));
        mvc.perform(get("/api/runs/"+a.id()).with(user("bob").roles("ANALYST"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/"+a.id()+"/events").with(user("bob").roles("ANALYST"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/runs/"+a.id()+"/approve").with(user("bob").roles("ANALYST")).with(csrf())).andExpect(status().isNotFound());
        assertThat(runs.get("alice",a.id()).status()).isEqualTo(Status.WAITING_FOR_REVIEW);
    }
    @Test void csrfAuthenticationAndRoleAreRequired() throws Exception {
        mvc.perform(get("/api/runs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runs").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/runs").with(user("alice").roles("ANALYST")).contentType("application/json")
            .header("Idempotency-Key","csrf-test-key").content(json.writeValueAsString(request("GMV",false))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/csrf").with(httpBasic("analyst","test-password-only")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }
    @Test void postApiAcceptsValidRequest() throws Exception {
        mvc.perform(post("/api/runs").with(user("alice").roles("ANALYST")).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(json.writeValueAsString(request("GMV",true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_FOR_REVIEW"));
    }
    @Test void noDataIsNotInvented() {
        Request q=new Request("GMV",LocalDate.of(2000,1,1),null,"GMV",false);
        Run a=completed(runs.submit("alice",UUID.randomUUID().toString(),q));
        assertThat(a.status()).isEqualTo(Status.FAILED);
        assertThat(a.errorCode()).isEqualTo("NO_DATA");
        assertThat(a.report()).isNull();
    }
    @Test void unsupportedQuestionFailsExplicitly() {
        Run a=completed(submit("帮我删除所有数据库"));
        assertThat(a.status()).isEqualTo(Status.FAILED);
        assertThat(a.errorCode()).isEqualTo("UNSUPPORTED_QUESTION");
    }
    @Test void trendDoesNotAddSevenDaysUv() {
        Run a=completed(submit("GMV 七天趋势"));
        assertThat(a.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(a.report().data().rows()).hasSize(21);
        assertThat(a.report().data().current().uv()).isEqualTo(3000);
    }
    @Test void definitionsDoNotQueryBusinessTables() {
        Run a=completed(submit("GMV 是什么"));
        assertThat(a.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(a.report().sql()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit WHERE run_id=?",Integer.class,a.id())).isZero();
    }
    @Test void topOrdersCategoriesByGmv() {
        Run a=completed(submit("GMV 排名"));
        assertThat(a.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(a.report().data().rows().getLast().category()).isEqualTo("appliances");
    }
    @Test void interruptedRunResumesPersistedSnapshot() {
        Run a=store.create("alice",UUID.randomUUID().toString(),request("GMV",false),"OFFLINE_RULES");
        assertThat(store.transition(a.id(),Status.QUEUED,Status.INTERRUPTED)).isTrue();
        Run done=completed(runs.resume("alice",a.id()));
        assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(store.snapshot(a.id()).completed).isEqualTo(6);
    }
    @Test void seedHasExpectedSizeAndMetricsMapperWorks() throws Exception {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_order",Integer.class)).isEqualTo(2696);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM traffic_daily",Integer.class)).isEqualTo(270);
        mvc.perform(get("/api/metrics").with(user("alice").roles("ANALYST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    }
}

