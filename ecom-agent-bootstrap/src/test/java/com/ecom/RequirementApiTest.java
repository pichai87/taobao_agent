package com.ecom;

import com.ecom.domain.Requirements.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RequirementApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Board board;
    @Autowired JdbcTemplate db;
    Review review(long version) {return new Review(false,"PARTIAL","AUTOMATED",true,"已检查部分实现，需要补充真实模型验收。",version);}
    @Test void catalogReflectsArchitectureWithoutPretendingComplete() throws Exception {
        mvc.perform(get("/api/requirements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/requirements").with(user("catalog-reader").roles("ANALYST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(28))
            .andExpect(jsonPath("$.referenceUrl").value("https://www.aixq.cc/67378.html"));
        var items=board.list("catalog-reader");
        assertThat(items.stream().filter(i->i.definition().id().equals("supervisor")).findFirst().orElseThrow().review().implemented()).isTrue();
        assertThat(items.stream().filter(i->i.definition().id().equals("semantic-mdl")).findFirst().orElseThrow().review().implemented()).isFalse();
        assertThat(items.stream().map(i->i.definition().id()).distinct().count()).isEqualTo(items.size());
    }
    @Test void mutationRequiresCsrfAndRejectsUnknownRequirement() throws Exception {
        mvc.perform(post("/api/requirements/tool-loop").with(user("reviewer").roles("ANALYST"))
            .contentType("application/json").content(json.writeValueAsString(review(0)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/requirements/nonexistent").with(user("reviewer").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(review(0)))).andExpect(status().isNotFound());
    }
    @Test void reviewPersistsWithOwnerIsolationAndAudit() throws Exception {
        mvc.perform(post("/api/requirements/tool-loop").with(user("board-alice").roles("ANALYST")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(review(0))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.review.version").value(1)).andExpect(jsonPath("$.review.rework").value(true));
        var own=board.list("board-alice").stream().filter(i->i.definition().id().equals("tool-loop")).findFirst().orElseThrow();
        assertThat(own.review().notes()).contains("真实模型");
        assertThat(own.review().implemented()).isFalse();
        var other=board.list("board-bob").stream().filter(i->i.definition().id().equals("tool-loop")).findFirst().orElseThrow();
        assertThat(other.review().version()).isZero();
        assertThat(other.review().implemented()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM requirement_review_audit WHERE owner='board-alice'",Integer.class)).isEqualTo(1);
    }
    @Test void staleWritesDoNotOverwriteOrAppendAudit() throws Exception {
        board.save("conflict-reader","tool-loop",review(0));
        for(long stale:new long[]{0,7}) {
            mvc.perform(post("/api/requirements/tool-loop").with(user("conflict-reader").roles("ANALYST")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review(stale))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_VERSION_CONFLICT"));
        }
        var next=board.save("conflict-reader","tool-loop",new Review(true,"IMPLEMENTED","LOCAL_E2E",false,"本机网页通过；外部模型尚未验收。",1));
        assertThat(next.review().version()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM requirement_review_audit WHERE owner='conflict-reader'",Integer.class)).isEqualTo(2);
    }
    @Test void rejectsInconsistentStatesAndAccidentalKeyInNotes() throws Exception {
        for(Review bad:new Review[]{new Review(true,"PARTIAL","AUTOMATED",false,"说明",0),
            new Review(false,"NONE","FAKE_LEVEL",false,"说明",0),
            new Review(false,"NONE","NOT_TESTED",false,"",0),
            new Review(false,"NONE","NOT_TESTED",false,"sk-test-only-not-a-real-key",0)}) {
            mvc.perform(post("/api/requirements/tool-loop").with(user("bad-reader").roles("ANALYST")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(bad))).andExpect(status().isBadRequest());
        }
    }
}
