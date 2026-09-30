package com.ecom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class QuestionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @Test void missingQuestionAndNullBodyReturnClientErrors() throws Exception {
        for (String body : new String[] {"null", "{}"}) {
            mvc.perform(post("/api/questions/preview").with(user("alice").roles("ANALYST")).with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            mvc.perform(post("/api/questions/query").with(user("alice").roles("ANALYST")).with(csrf())
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test void previewAndQueryRequireAuthenticationAndCsrf() throws Exception {
        String body = "{\"question\":\"查询2026-09-12 GMV\"}";
        mvc.perform(post("/api/questions/preview").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/questions/preview").with(user("alice").roles("ANALYST"))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/questions/query").with(user("alice").roles("ANALYST"))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
    }

    @Test void validQuestionCompilesAndQueriesTheApprovedSemanticModel() throws Exception {
        String body = "{\"question\":\"查询2026-09-12 GMV\"}";
        mvc.perform(post("/api/questions/preview").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.queryExecutable").value(true))
            .andExpect(jsonPath("$.plans.length()").value(1));
        mvc.perform(post("/api/questions/query").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].period").value("current"))
            .andExpect(jsonPath("$.data[0].rows.length()").value(1));
    }

    @Test void crossDateMetricBindingNeedsClarificationAndCannotReadData() throws Exception {
        String body = "{\"question\":\"2026-09-12 GMV对比2026-09-11 UV\"}";
        int before = db.queryForObject("SELECT COUNT(*) FROM semantic_change_audit WHERE action='QUERY'", Integer.class);
        mvc.perform(post("/api/questions/preview").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
            .andExpect(jsonPath("$.queryExecutable").value(false))
            .andExpect(jsonPath("$.understanding.clarifications[0].code").value("METRIC_DATE_BINDING_AMBIGUOUS"));
        mvc.perform(post("/api/questions/query").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUESTION_NEEDS_CLARIFICATION"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM semantic_change_audit WHERE action='QUERY'", Integer.class)).isEqualTo(before);
    }

    @Test void topNReturnsOnlyTheRequestedHighestCategories() throws Exception {
        String body = "{\"question\":\"2026-09-12 GMV前2名\"}";
        mvc.perform(post("/api/questions/query").with(user("alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preview.understanding.topN").value(2))
            .andExpect(jsonPath("$.data[0].rows.length()").value(2));
    }
}
