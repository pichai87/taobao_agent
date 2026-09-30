package com.ecom;

import com.ecom.domain.Lineage;
import com.ecom.domain.BusinessException;
import com.ecom.domain.Ports.Knowledge;
import com.ecom.infrastructure.JdbcLineageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class LineageApiTest {
    @Autowired Lineage.Service lineage;
    @Autowired Knowledge knowledge;
    @Autowired MockMvc mvc;
    @Test void parsesTheActualVersionedViewInsteadOfAHandwrittenExplanation() {
        var model=lineage.model();
        assertThat(model.version()).isEqualTo("V4");
        var amount=model.lineage().columns().stream().filter(c->c.output().equals("gmv")).findFirst().orElseThrow();
        assertThat(amount.sources()).contains(new Lineage.SourceColumn("biz_order","paid_amount"));
        assertThat(model.lineage().sourceTables()).containsExactlyInAnyOrder("biz_order","traffic_daily");
        assertThat(model.lineage().predicates()).anyMatch(p->p.kind().equals("WHERE") && p.sources().contains(new Lineage.SourceColumn("biz_order","status")));
    }
    @Test void queryExpertReceivesVerifiedAstEvidence() {
        assertThat(knowledge.context("GMV 口径","GMV")).anyMatch(e->e.id().equals("ast-gmv") && e.text().contains("paid_amount"));
    }
    @Test void actualMetadataIsFilteredByServerApprovedColumns() {
        assertThat(lineage.schema()).containsOnlyKeys("analytics_daily","biz_order","traffic_daily");
        assertThat(lineage.schema().get("biz_order")).doesNotContain("customer_ref");
        assertThatThrownBy(()->lineage.analyze("SELECT customer_ref FROM biz_order")) .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->lineage.analyze("SELECT * FROM agent_run")) .isInstanceOf(BusinessException.class);
    }
    @Test void missingMetadataIsNotSilentlyInferred() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:missing_schema_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
        assertThatThrownBy(()->new JdbcLineageService(source).schema()).hasMessage("LINEAGE_SCHEMA_DRIFT");
    }
    @Test void apiRetainsAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/lineage/model")).andExpect(status().isUnauthorized());
        String body="{\"sql\":\"SELECT SUM(paid_amount) AS gmv FROM biz_order\"}";
        mvc.perform(post("/api/lineage/analyze").with(user("alice").roles("ANALYST")).contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/lineage/analyze").with(user("alice").roles("ANALYST")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.columns[0].sources[0].column").value("paid_amount"));
    }
    @Test void clientsCannotExtendTheSchemaOrExecuteWrites() throws Exception {
        mvc.perform(post("/api/lineage/analyze").with(user("alice").roles("ANALYST")).with(csrf()).contentType("application/json")
            .content("{\"sql\":\"SELECT * FROM agent_run\",\"schema\":{\"agent_run\":[\"owner\"]}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/lineage/analyze").with(user("alice").roles("ANALYST")).with(csrf()).contentType("application/json")
            .content("{\"sql\":\"DELETE FROM biz_order\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINEAGE_SELECT_ONLY"));
    }

    @Test void nullOrMissingSqlUsesTheStandardClientError() throws Exception {
        for (String body : new String[] {"null", "{}"})
            mvc.perform(post("/api/lineage/analyze").with(user("alice").roles("ANALYST")).with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
