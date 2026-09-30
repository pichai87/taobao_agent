package com.ecom;

import com.ecom.domain.BusinessException;
import com.ecom.domain.KnowledgePublishing.*;
import com.ecom.domain.Ports.Knowledge;
import com.ecom.domain.Semantic;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class KnowledgePublishingTest {
    @Autowired Service publishing;
    @Autowired Knowledge knowledge;
    @Autowired Semantic.Workbench workbench;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    String owner() { return "publisher-" + UUID.randomUUID(); }
    Content content(String metric) { return new Content(Layer.L3_RULE, metric, "人工确认的支付口径", "仅统计 PAID 订单，不将退款后的净收入当成 GMV。", ""); }
    Review confirmed(Draft draft) { return new Review(draft.version(), true, "已逐项检查口径、内容和 SQL 边界，确认此版本。 "); }
    String registerMetric() {
        String metric = "PUB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        workbench.register("test", new Semantic.MetricDef(metric, "审核测试指标", Semantic.Formula.SUM, "gmv", null, "元", "测试隔离指标"));
        return metric;
    }

    @Test void draftsAreInvisibleUntilHumanPublicationAndThenBecomeAgentEvidence() {
        String owner = owner(), metric = registerMetric();
        Draft draft = publishing.create(owner, content(metric));
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThat(draft.version()).isEqualTo(1);
        assertThat(draft.publishedDocumentId()).isNull();
        assertThat(knowledge.recall("口径", metric)).isEmpty();
        Draft published = publishing.publish(owner, draft.id(), confirmed(draft));
        assertThat(published.status()).isEqualTo(Status.PUBLISHED);
        assertThat(knowledge.recall("口径", metric)).extracting(e -> e.id()).contains(published.publishedDocumentId());
        assertThat(workbench.recall(metric, "口径")).extracting(Semantic.KnowledgeHit::id).contains(published.publishedDocumentId());
        assertThat(publishing.audit(owner, draft.id())).extracting(Audit::action).containsExactly("CREATE", "PUBLISH");
        assertThat(publishing.audit(owner, draft.id()).getLast().note()).contains("逐项检查");
    }

    @Test void ownerIsCheckedForEveryDraftOperationIncludingAuditAndRevision() {
        String alice = owner(), bob = owner();
        Draft draft = publishing.create(alice, content("GMV"));
        assertThat(publishing.list(bob)).isEmpty();
        List<Runnable> forbidden = List.of(
            () -> publishing.get(bob, draft.id()),
            () -> publishing.edit(bob, draft.id(), new Edit(1, content("GMV"))),
            () -> publishing.publish(bob, draft.id(), confirmed(draft)),
            () -> publishing.reject(bob, draft.id(), confirmed(draft)),
            () -> publishing.revise(bob, draft.id(), new Revision(1)),
            () -> publishing.audit(bob, draft.id()));
        forbidden.forEach(action -> assertThatThrownBy(action::run).hasMessage("KNOWLEDGE_NOT_FOUND"));
        assertThat(publishing.get(alice, draft.id()).version()).isEqualTo(1);
    }

    @Test void apiRequiresAuthenticationRoleCsrfAndReturnsOpaqueForeignDraft404() throws Exception {
        mvc.perform(get("/api/knowledge/drafts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/knowledge/drafts").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        String body = json.writeValueAsString(content("GMV"));
        mvc.perform(post("/api/knowledge/drafts").with(user("reader").roles("ANALYST"))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge/drafts").with(user("reader").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        Draft draft = publishing.create(owner(), content("GMV"));
        mvc.perform(get("/api/knowledge/drafts/" + draft.id()).with(user("intruder").roles("ANALYST")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_FOUND"));
        mvc.perform(post("/api/knowledge/drafts/" + draft.id() + "/publish").with(user("intruder").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(confirmed(draft)))).andExpect(status().isNotFound());
    }

    @Test void staleVersionsCannotOverwriteOrApproveAndDoNotAppendAudit() {
        String owner = owner();
        Draft draft = publishing.create(owner, content("GMV"));
        Draft edited = publishing.edit(owner, draft.id(), new Edit(1, new Content(Layer.L3_RULE, "GMV", "新标题", "新口径", "")));
        assertThat(edited.version()).isEqualTo(2);
        assertThatThrownBy(() -> publishing.edit(owner, draft.id(), new Edit(1, content("GMV")))).hasMessage("KNOWLEDGE_VERSION_CONFLICT");
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), confirmed(draft))).hasMessage("KNOWLEDGE_VERSION_CONFLICT");
        assertThat(publishing.get(owner, draft.id()).title()).isEqualTo("新标题");
        assertThat(publishing.audit(owner, draft.id())).hasSize(2);
    }

    @Test void alreadyPublishedDraftIsImmutableAndCannotBePublishedTwice() {
        String owner = owner();
        Draft draft = publishing.create(owner, content("GMV"));
        Draft published = publishing.publish(owner, draft.id(), confirmed(draft));
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), confirmed(published))).hasMessage("KNOWLEDGE_STATE_CONFLICT");
        assertThatThrownBy(() -> publishing.reject(owner, draft.id(), confirmed(published))).hasMessage("KNOWLEDGE_STATE_CONFLICT");
        assertThatThrownBy(() -> publishing.edit(owner, draft.id(), new Edit(published.version(), content("GMV")))).hasMessage("KNOWLEDGE_STATE_CONFLICT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id=?", Integer.class, published.publishedDocumentId())).isEqualTo(1);
        assertThat(publishing.audit(owner, draft.id())).hasSize(2);
    }

    @Test void approvalAndRejectionRequireExplicitConfirmationAndNonemptyReviewNote() {
        String owner = owner();
        Draft draft = publishing.create(owner, content("GMV"));
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), new Review(1, false, "确认"))).hasMessage("KNOWLEDGE_CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> publishing.reject(owner, draft.id(), new Review(1, false, "拒绝"))).hasMessage("KNOWLEDGE_CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), new Review(1, true, " "))).hasMessage("INVALID_KNOWLEDGE_CONTENT");
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), new Review(1, true, "api_key=secret-value"))).hasMessage("INVALID_KNOWLEDGE_CONTENT");
        assertThat(publishing.get(owner, draft.id()).status()).isEqualTo(Status.DRAFT);
        assertThat(publishing.audit(owner, draft.id())).hasSize(1);
    }

    @Test void rejectedKnowledgeNeverBecomesRetrievableAndKeepsAudit() {
        String owner = owner(), metric = registerMetric();
        Draft draft = publishing.create(owner, content(metric));
        Draft rejected = publishing.reject(owner, draft.id(), new Review(1, true, "口径证据不够，打回重写。"));
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.publishedDocumentId()).isNull();
        assertThat(knowledge.recall("口径", metric)).isEmpty();
        assertThat(publishing.audit(owner, draft.id()).getLast().action()).isEqualTo("REJECT");
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), confirmed(rejected))).hasMessage("KNOWLEDGE_STATE_CONFLICT");
    }

    @Test void validatesRegisteredMetricLayerContentLimitsAndAccidentalSecrets() throws Exception {
        String owner = owner();
        assertThatThrownBy(() -> publishing.create(owner, content("NOT_REGISTERED"))).hasMessage("KNOWLEDGE_METRIC_NOT_REGISTERED");
        for (Content invalid : List.of(
            new Content(null, "GMV", "标题", "内容", ""),
            new Content(Layer.L3_RULE, "GMV", "标题", " ", ""),
            new Content(Layer.L3_RULE, "GMV", "标题", "x".repeat(6001), ""),
            new Content(Layer.L3_RULE, "GMV", "标题", "api_key=secret-value", ""),
            new Content(Layer.L3_RULE, "GMV", "标题", "内容", "x".repeat(16001)))) {
            assertThatThrownBy(() -> publishing.create(owner, invalid)).hasMessage("INVALID_KNOWLEDGE_CONTENT");
        }
        mvc.perform(post("/api/knowledge/drafts").with(user(owner).roles("ANALYST")).with(csrf())
            .contentType("application/json").content("{\"layer\":\"L6_SESSION\",\"metric\":\"GMV\",\"title\":\"x\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
        assertThat(publishing.list(owner)).isEmpty();
    }

    @Test void allFourLayersAndWildcardCanBePublishedButSqlIsOnlyParsed() {
        String owner = owner();
        int orders = db.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class);
        int queryAudits = db.queryForObject("SELECT COUNT(*) FROM tool_call_audit", Integer.class);
        for (Layer layer : Layer.values()) {
            String sql = layer == Layer.L2_SQL ? "SELECT SUM(paid_amount) AS revenue FROM biz_order WHERE status='PAID'" : "";
            Draft draft = publishing.create(owner, new Content(layer, "*", "人工知识 " + layer, "经人工检查的通用口径说明。", sql));
            if (!sql.isEmpty()) {
                assertThat(draft.sqlLineage().sourceTables()).containsExactly("biz_order");
                assertThat(draft.sqlLineage().columns().getFirst().sources()).extracting(s -> s.column()).containsExactly("paid_amount");
            }
            Draft published = publishing.publish(owner, draft.id(), confirmed(draft));
            assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, published.publishedDocumentId())).isTrue();
            if (layer == Layer.L3_RULE)
                assertThat(knowledge.recall("口径", "GMV")).extracting(e -> e.id()).contains(published.publishedDocumentId());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_order", Integer.class)).isEqualTo(orders);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tool_call_audit", Integer.class)).isEqualTo(queryAudits);
    }

    @Test void unsafeOrOutOfSchemaSqlCannotBeSavedOrApproved() {
        String owner = owner();
        for (String sql : List.of("DELETE FROM biz_order", "SELECT customer_ref FROM biz_order", "SELECT * FROM knowledge_document",
            "SELECT 1 AS x; DROP TABLE biz_order", "SELECT missing FROM analytics_daily")) {
            assertThatThrownBy(() -> publishing.create(owner, new Content(Layer.L2_SQL, "GMV", "待审 SQL", "说明", sql)))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException)e).code()).startsWith("LINEAGE_"));
        }
        assertThat(publishing.list(owner)).isEmpty();
    }

    @Test void publicationRevalidatesRegistryRatherThanTrustingEarlierDraftValidation() {
        String owner = owner(), metric = registerMetric();
        Draft draft = publishing.create(owner, content(metric));
        db.update("DELETE FROM metric_definition WHERE code=?", metric);
        assertThatThrownBy(() -> publishing.publish(owner, draft.id(), confirmed(draft))).hasMessage("KNOWLEDGE_METRIC_NOT_REGISTERED");
        assertThat(publishing.get(owner, draft.id()).status()).isEqualTo(Status.DRAFT);
        assertThat(publishing.audit(owner, draft.id())).hasSize(1);
    }

    @Test void revisionCreatesNewDraftAndPreservesHistoryWhileOnlyLatestVersionIsRetrieved() {
        String owner = owner(), metric = registerMetric();
        Draft initial = publishing.create(owner, content(metric));
        Draft first = publishing.publish(owner, initial.id(), confirmed(initial));
        Draft revision = publishing.revise(owner, first.id(), new Revision(first.version()));
        assertThat(revision.id()).isNotEqualTo(first.id());
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.supersedesDocumentId()).isEqualTo(first.publishedDocumentId());
        assertThat(knowledge.recall("口径", metric)).extracting(e -> e.id()).contains(first.publishedDocumentId());
        revision = publishing.edit(owner, revision.id(), new Edit(revision.version(), new Content(Layer.L3_RULE, metric, "新版口径", "新版补充：支付时间不是创建时间。", "")));
        Draft second = publishing.publish(owner, revision.id(), confirmed(revision));
        assertThat(db.queryForObject("SELECT content FROM knowledge_document WHERE id=?", String.class, first.publishedDocumentId())).isEqualTo(first.content());
        assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, first.publishedDocumentId())).isFalse();
        assertThat(db.queryForObject("SELECT version FROM knowledge_document WHERE id=?", String.class, second.publishedDocumentId())).isEqualTo("2");
        assertThat(knowledge.recall("口径", metric)).extracting(e -> e.id()).contains(second.publishedDocumentId()).doesNotContain(first.publishedDocumentId());
        assertThat(workbench.recall(metric, "口径")).extracting(Semantic.KnowledgeHit::id).contains(second.publishedDocumentId()).doesNotContain(first.publishedDocumentId());
        assertThatThrownBy(() -> publishing.revise(owner, first.id(), new Revision(first.version()))).hasMessage("KNOWLEDGE_REVISION_CONFLICT");
    }

    @Test void revisionMayChangeContentButCannotChangeItsMetricOrLayer() throws Exception {
        String owner = owner();
        Draft initial = publishing.create(owner, content("GMV"));
        Draft first = publishing.publish(owner, initial.id(), confirmed(initial));
        Draft revision = publishing.revise(owner, first.id(), new Revision(first.version()));
        Content otherMetric = new Content(Layer.L3_RULE, "ORDERS", "订单新口径", "另一个指标的说明", "");
        Content otherLayer = new Content(Layer.L2_SQL, "GMV", "SQL 新示例", "另一个知识层", "");
        assertThatThrownBy(() -> publishing.edit(owner, revision.id(), new Edit(revision.version(), otherMetric)))
            .hasMessage("KNOWLEDGE_REVISION_IDENTITY_CONFLICT");
        assertThatThrownBy(() -> publishing.edit(owner, revision.id(), new Edit(revision.version(), otherLayer)))
            .hasMessage("KNOWLEDGE_REVISION_IDENTITY_CONFLICT");
        mvc.perform(put("/api/knowledge/drafts/" + revision.id()).with(user(owner).roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(new Edit(revision.version(), otherMetric))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_REVISION_IDENTITY_CONFLICT"));
        assertThat(publishing.get(owner, revision.id()).version()).isEqualTo(1);
        assertThat(publishing.audit(owner, revision.id())).hasSize(1);
        assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, first.publishedDocumentId())).isTrue();

        Draft edited = publishing.edit(owner, revision.id(), new Edit(revision.version(),
            new Content(Layer.L3_RULE, "GMV", "同一指标的新标题", "同一指标的新正文", "")));
        Draft published = publishing.publish(owner, revision.id(), confirmed(edited));
        assertThat(published.metric()).isEqualTo("GMV");
        assertThat(published.layer()).isEqualTo(Layer.L3_RULE);
        assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, first.publishedDocumentId())).isFalse();
        assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, published.publishedDocumentId())).isTrue();
    }

    @Test void publishRechecksRevisionIdentityAfterStoredDraftWasTamperedWith() {
        String owner = owner();
        Draft initial = publishing.create(owner, content("GMV"));
        Draft first = publishing.publish(owner, initial.id(), confirmed(initial));
        Draft revision = publishing.revise(owner, first.id(), new Revision(first.version()));
        db.update("UPDATE knowledge_draft SET metric_code='ORDERS' WHERE id=?", revision.id());
        assertThatThrownBy(() -> publishing.publish(owner, revision.id(), confirmed(revision)))
            .hasMessage("KNOWLEDGE_REVISION_IDENTITY_CONFLICT");
        assertThat(publishing.get(owner, revision.id()).status()).isEqualTo(Status.DRAFT);
        assertThat(publishing.audit(owner, revision.id())).hasSize(1);
        assertThat(db.queryForObject("SELECT active FROM knowledge_document WHERE id=?", Boolean.class, first.publishedDocumentId())).isTrue();
    }

    @Test void httpVersionConflictIs409AndRejectHasNoImplicitConfirmation() throws Exception {
        String owner = owner();
        Draft draft = publishing.create(owner, content("GMV"));
        mvc.perform(put("/api/knowledge/drafts/" + draft.id()).with(user(owner).roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(new Edit(999, content("GMV")))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KNOWLEDGE_VERSION_CONFLICT"));
        mvc.perform(post("/api/knowledge/drafts/" + draft.id() + "/reject").with(user(owner).roles("ANALYST")).with(csrf())
            .contentType("application/json").content("{\"expectedVersion\":1,\"note\":\"打回\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("KNOWLEDGE_CONFIRMATION_REQUIRED"));
    }

    @Test void longPublishedKnowledgeIsBoundedBeforeEnteringAgentContextButStoredInFull() {
        String owner = owner(), metric = registerMetric();
        String original = "支付口径归因".repeat(900);
        var documentIds = new java.util.ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            Draft draft = publishing.create(owner, new Content(Layer.L3_RULE, metric, "口径归因规则 " + i, original, ""));
            documentIds.add(publishing.publish(owner, draft.id(), confirmed(draft)).publishedDocumentId());
        }
        var context = knowledge.context("归因口径", metric);
        assertThat(context).extracting(e -> e.id()).contains("ast-gmv");
        assertThat(context).anyMatch(e -> e.text().contains("模型上下文已截断"));
        assertThat(context.stream().mapToInt(e -> e.id().length() + e.title().length() + e.version().length() + e.text().length() + 32).sum())
            .isLessThanOrEqualTo(14_000);
        assertThat(db.queryForObject("SELECT content FROM knowledge_document WHERE id=?", String.class, documentIds.getFirst()))
            .isEqualTo(original);
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRevisionsHaveOneWinnerAndFailedTransactionDoesNotPublishOrRetireWinner() throws Exception {
        // An isolated test metric avoids influencing other test cases; this test needs real commits for two connections.
        String owner = owner(), metric = registerMetric();
        Draft initial = publishing.create(owner, content(metric));
        Draft first = publishing.publish(owner, initial.id(), confirmed(initial));
        Draft a = publishing.revise(owner, first.id(), new Revision(first.version()));
        Draft b = publishing.revise(owner, first.id(), new Revision(first.version()));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = List.of(a,b).stream().map(draft -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
                try { publishing.publish(owner, draft.id(), confirmed(draft)); return "PUBLISHED"; }
                catch (BusinessException e) { return e.code(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(outcomes.get(0).get(10, TimeUnit.SECONDS), outcomes.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("PUBLISHED", "KNOWLEDGE_REVISION_CONFLICT");
        }
        var revisions = List.of(publishing.get(owner,a.id()), publishing.get(owner,b.id()));
        assertThat(revisions).extracting(Draft::status).containsExactlyInAnyOrder(Status.PUBLISHED, Status.DRAFT);
        Draft loser = revisions.stream().filter(d -> d.status() == Status.DRAFT).findFirst().orElseThrow();
        assertThat(loser.version()).isEqualTo(1);
        assertThat(loser.publishedDocumentId()).isNull();
        assertThat(publishing.audit(owner, loser.id())).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE metric_code=? AND active=TRUE", Integer.class, metric)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE metric_code=?", Integer.class, metric)).isEqualTo(2);
    }
}
