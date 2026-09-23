package com.ecom;

import com.ecom.domain.Analysis.Event;
import com.ecom.domain.Analysis.Request;
import com.ecom.domain.Analysis.Run;
import com.ecom.domain.Analysis.Status;
import com.ecom.domain.Ports.RunStore;
import com.ecom.domain.Semantic.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual authenticated MVC + database + scheduled SSE emitter tests, not only a frame parser. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebProtocolReviewTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RunStore store;
    @Autowired JdbcTemplate db;

    private static final LocalDate DATE = LocalDate.of(2026, 9, 12);
    private static final Pattern EVENT_SEQUENCE = Pattern.compile("\"sequence\"\\s*:\\s*(\\d+)");

    @Test void allNewWriteEndpointsKeepAuthenticationRoleAndCsrfBoundaries() throws Exception {
        mvc.perform(get("/api/semantic/model")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/semantic/knowledge").param("metric", "GMV")
            .with(user("review-viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        for (String endpoint : List.of("plan", "query", "metrics", "memory")) {
            mvc.perform(post("/api/semantic/" + endpoint)
                .with(user("review-analyst").roles("ANALYST"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/semantic/" + endpoint)
                .with(user("review-viewer").roles("VIEWER")).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test void postedOwnerCannotOverwriteAnotherUsersMemory() throws Exception {
        String alice = "review-alice-" + UUID.randomUUID();
        String bob = "review-bob-" + UUID.randomUUID();
        mvc.perform(post("/api/semantic/memory").with(user(alice).roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(
                Map.of("owner", bob, "summary", "synthetic private note"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("synthetic private note"));
        mvc.perform(get("/api/semantic/memory").with(user(bob).roles("ANALYST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value(""));
        mvc.perform(get("/api/semantic/memory").param("owner", alice).with(user(bob).roles("ANALYST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value(""));
    }

    @Test void semanticApiBindsFilterTextAndRejectsUnauthorizedProjection() throws Exception {
        Query injected = new Query(List.of("GMV"), List.of("category"), DATE, DATE, "x' OR 1=1 --", 10);
        mvc.perform(post("/api/semantic/query").with(user("review-sql").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(injected)))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        Query privateField = new Query(List.of("GMV"), List.of("customer_ref"), DATE, DATE, null, 10);
        mvc.perform(post("/api/semantic/query").with(user("review-sql").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(privateField)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SEMANTIC_PLAN"));
        mvc.perform(post("/api/semantic/query").with(user("review-sql").roles("ANALYST")).with(csrf())
            .contentType("application/json").content("{\"sql\":\"SELECT customer_ref FROM biz_order\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void sseCannotReadAnotherOwnersRunEvenWithACursor() throws Exception {
        Run run = waitingRun();
        store.event(run.id(), "review", "STARTED", "private synthetic event", 0);
        mvc.perform(get("/api/runs/" + run.id() + "/stream").param("after", "0")
            .header("Last-Event-ID", "0").with(user(run.owner() + "-other").roles("ANALYST")))
            .andExpect(status().isNotFound());
    }

    @Test void actualSseReplaysFromCursorAndReconnectDoesNotRepeatDeliveredEvents() throws Exception {
        Run run = waitingRun();
        for (int i = 0; i < 3; i++) store.event(run.id(), "review", "SUCCEEDED", "synthetic-" + i, 0);
        List<Event> events = store.events(run.id(), 0);
        String first = stream(run, events.getFirst().sequence(), null);
        assertThat(sequences(first)).containsExactly(events.get(1).sequence(), events.get(2).sequence());
        assertThat(first).contains("event:status", "WAITING_FOR_REVIEW");

        String reconnect = stream(run, events.getLast().sequence(), null);
        assertThat(sequences(reconnect)).isEmpty();
        assertThat(reconnect).contains("WAITING_FOR_REVIEW");
    }

    @Test void lastEventIdHeaderTakesPrecedenceOverQueryCursor() throws Exception {
        Run run = waitingRun();
        for (int i = 0; i < 3; i++) store.event(run.id(), "review", "SUCCEEDED", "synthetic-" + i, 0);
        List<Event> events = store.events(run.id(), 0);
        String body = stream(run, 0, events.get(1).sequence());
        assertThat(sequences(body)).containsExactly(events.getLast().sequence());
    }

    @Test void terminalSseDrainsEveryPersistedPageBeforeClosing() throws Exception {
        Run run = waitingRun();
        for (int i = 0; i < 1001; i++) store.event(run.id(), "review", "SUCCEEDED", "page-event-" + i, 0);
        assertThat(store.events(run.id(), 0)).hasSize(1000);
        String body = stream(run, 0, null);
        List<Long> delivered = sequences(body);
        assertThat(delivered).hasSize(1001).doesNotHaveDuplicates().isSorted();
        assertThat(body.lastIndexOf("event:status")).isGreaterThan(body.lastIndexOf("event:node"));
    }

    @Test void failedCompareAndSetCannotCreateDuplicateOrFalseTerminalEvents() {
        Run run = waitingRun();
        assertThat(store.cancelWithEvent(run.id(), Status.WAITING_FOR_REVIEW)).isTrue();
        assertThat(store.cancelWithEvent(run.id(), Status.WAITING_FOR_REVIEW)).isFalse();
        store.failWithEvent(run.id(), "LATE_WORKER_ERROR");
        assertThat(store.find(run.id(), run.owner()).orElseThrow().status()).isEqualTo(Status.CANCELLED);
        assertThat(store.events(run.id(), 0)).extracting(Event::status).containsExactly("CANCELLED");
    }

    @Test void repeatedFailureDoesNotReplaceTheOriginalOutcomeOrDuplicateItsEvent() {
        Run run = waitingRun();
        assertThat(store.transition(run.id(), Status.WAITING_FOR_REVIEW, Status.QUEUED)).isTrue();
        store.failWithEvent(run.id(), "FIRST_FAILURE");
        store.failWithEvent(run.id(), "SECOND_FAILURE");
        Run failed = store.find(run.id(), run.owner()).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("FIRST_FAILURE");
        assertThat(store.events(run.id(), 0)).extracting(Event::detail).containsExactly("FIRST_FAILURE");
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "fail"})
    void failedEventPersistenceRollsBackItsTerminalState(String action) {
        Run run = waitingRun();
        Status original = Status.WAITING_FOR_REVIEW;
        if (action.equals("fail")) {
            assertThat(store.transition(run.id(), original, Status.QUEUED)).isTrue();
            original = Status.QUEUED;
        }
        String constraint = "review_event_" + UUID.randomUUID().toString().replace("-", "");
        // Only this synthetic run is rejected. Other tests' rows are unaffected, and the
        // temporary check is always removed from the isolated test database in finally.
        db.execute("ALTER TABLE agent_event ADD CONSTRAINT " + constraint + " CHECK (run_id <> '" + run.id() + "')");
        try {
            assertThatThrownBy(() -> {
                if (action.equals("cancel")) store.cancelWithEvent(run.id(), Status.WAITING_FOR_REVIEW);
                else store.failWithEvent(run.id(), "SYNTHETIC_FAILURE");
            }).isInstanceOf(DataAccessException.class);
            Run unchanged = store.find(run.id(), run.owner()).orElseThrow();
            assertThat(unchanged.status()).isEqualTo(original);
            assertThat(unchanged.errorCode()).isNull();
            assertThat(store.events(run.id(), 0)).isEmpty();
        } finally {
            db.execute("ALTER TABLE agent_event DROP CONSTRAINT " + constraint);
        }
    }

    private Run waitingRun() {
        String owner = "sse-review-" + UUID.randomUUID();
        return store.create(owner, UUID.randomUUID().toString(),
            new Request("GMV", DATE, DATE.minusDays(1), "GMV", true), "OFFLINE_RULES");
    }

    private String stream(Run run, long after, Long header) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/runs/" + run.id() + "/stream")
            .param("after", Long.toString(after)).with(user(run.owner()).roles("ANALYST"));
        if (header != null) request.header("Last-Event-ID", header);
        MvcResult pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(15000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Long> sequences(String stream) {
        var ids = new ArrayList<Long>();
        var matcher = EVENT_SEQUENCE.matcher(stream);
        while (matcher.find()) ids.add(Long.parseLong(matcher.group(1)));
        return ids;
    }
}
