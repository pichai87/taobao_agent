package com.ecom;

import com.ecom.domain.Semantic.*;
import com.ecom.domain.Ports.AnalyticsReader;
import com.ecom.domain.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class SemanticWorkbenchTest {
    @Autowired Workbench workbench;
    @Autowired AnalyticsReader reader;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final LocalDate DATE=LocalDate.of(2026,9,12);
    Query query(List<String> metrics,List<String> dimensions) {return new Query(metrics,dimensions,DATE,DATE,null,500);}
    @Test void realDatabasePlanAndQueryReturnsWeightedRegisteredMetrics() {
        Query q=query(List.of("GMV","ORDERS","UV","CVR","AOV"),List.of());
        var plan=workbench.plan(q);
        assertThat(plan.lineage()).hasSize(5);
        assertThat(plan.sql()).contains("NULLIF", "BETWEEN ? AND ?").doesNotContain("2026");
        var rows=workbench.query("alice",q);
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal)rows.getFirst().get("gmv")).isEqualByComparingTo("2600");
        assertThat(new BigDecimal(rows.getFirst().get("orders").toString())).isEqualByComparingTo("26");
        assertThat((BigDecimal)rows.getFirst().get("aov")).isEqualByComparingTo("100");
    }
    @Test void allExistingAgentReadersNowUseSemanticCompiler() {
        assertThat(reader.generatedSql()).contains("SUM(gmv)","GROUP BY stat_date, category");
        var rows=reader.query("semantic-integration",DATE);
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r->r.gmv()).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("2600");
    }
    @Test void registryPersistsAliasAndMakesItQueryableWithoutCodeChanges() {
        String code="REV_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        var d=new MetricDef(code,"每访客收入",Formula.RATIO,"gmv","uv","元/人","合成互斥客群上的收入比值");
        assertThat(workbench.register("alice",d)).isEqualTo(d);
        assertThat(workbench.model().metrics()).contains(d);
        assertThat(workbench.query("alice",query(List.of(code),List.of())).getFirst()).containsKey(code.toLowerCase(Locale.ROOT));
        assertThatThrownBy(()->workbench.register("alice",d)).isInstanceOf(BusinessException.class).hasMessage("METRIC_ALREADY_EXISTS");
    }
    @Test void missingAndZeroDataHaveDifferentSemantics() {
        Query q=new Query(List.of("GMV","CVR"),List.of("category"),DATE.minusYears(10),DATE.minusYears(10),null,500);
        assertThat(workbench.query("alice",q)).isEmpty();
    }
    @Test void knowledgeLayersAreScopedAndBounded() {
        var hits=workbench.recall("CVR","口径");
        assertThat(hits).hasSizeLessThanOrEqualTo(12);
        assertThat(hits).extracting(KnowledgeHit::layer).contains("L1_MODEL","L2_SQL","L3_RULE","L5_METRIC");
        assertThat(hits).extracting(KnowledgeHit::id).doesNotContain("lineage-gmv","rule-aov");
    }
    @Test void manualMemoryNeverLeaksAcrossOwners() {
        String owner="memory-"+UUID.randomUUID();
        workbench.remember(owner,"已学习 GMV 口径");
        assertThat(workbench.memory(owner)).isEqualTo("已学习 GMV 口径");
        assertThat(workbench.memory(owner+"-other")).isEmpty();
        assertThatThrownBy(()->workbench.remember(owner,"sk-test-dont-store")) .hasMessage("INVALID_MEMORY");
    }
    @Test void apiRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/semantic/model")).andExpect(status().isUnauthorized());
        String body=json.writeValueAsString(query(List.of("GMV"),List.of()));
        mvc.perform(post("/api/semantic/query").with(user("alice").roles("ANALYST")).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/semantic/query").with(user("alice").roles("ANALYST")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].gmv").value(2600));
    }
    @Test void sqlStringsCannotBeSubmittedInsteadOfSemanticFields() throws Exception {
        mvc.perform(post("/api/semantic/plan").with(user("alice").roles("ANALYST")).with(csrf()).contentType("application/json")
            .content("{\"sql\":\"SELECT * FROM biz_order\"}"))
            .andExpect(status().isBadRequest());
    }
}
