package com.ecom;

import com.ecom.agent.AnalysisWorkflow;
import com.ecom.domain.*;
import com.ecom.domain.Ports.*;
import com.ecom.infrastructure.ModelPlanner;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.time.LocalDate;
import static com.ecom.domain.Analysis.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class RecoveryAndModelTest {
    @Autowired RunStore store;
    @Autowired AnalysisWorkflow workflow;
    @Autowired Knowledge knowledge;
    @Test void resumeSkipsTwoCompletedSteps() throws Exception {
        Request request=new Request("GMV",LocalDate.of(2026,9,12),null,"GMV",false);
        Run run=store.create("recovery-test",UUID.randomUUID().toString(),request,"OFFLINE_RULES");
        store.transition(run.id(),Status.QUEUED,Status.RUNNING);
        Snapshot state=new Snapshot();state.completed=2;
        state.plan=new Plan(Intent.QUERY,List.of("persisted-plan"),"OFFLINE_RULES");
        state.evidence=knowledge.recall("GMV","GMV");
        store.checkpoint(run.id(),state);
        store.transition(run.id(),Status.RUNNING,Status.INTERRUPTED);
        store.transition(run.id(),Status.INTERRUPTED,Status.RUNNING);
        workflow.execute(run);
        assertThat(store.find(run.id(),run.owner()).orElseThrow().status()).isEqualTo(Status.SUCCEEDED);
        assertThat(store.events(run.id(),0)).noneMatch(e->e.node().equals("plan") || e.node().equals("semantic"));
        assertThat(store.snapshot(run.id()).plan.steps()).containsExactly("persisted-plan");
    }
    @Test void cancelledCheckpointCannotOverwriteState() {
        Run run=store.create("cancel-test",UUID.randomUUID().toString(),
            new Request("GMV",LocalDate.now(),null,"GMV",false),"OFFLINE_RULES");
        store.transition(run.id(),Status.QUEUED,Status.CANCELLED);
        assertThatThrownBy(()->store.checkpoint(run.id(),new Snapshot())).hasMessage("RUN_STOPPED");
        store.fail(run.id(),"SHOULD_NOT_REPLACE_CANCEL");
        assertThat(store.find(run.id(),run.owner()).orElseThrow().status()).isEqualTo(Status.CANCELLED);
    }
    @Test void modelAdapterAcceptsEnumAndRejectsExecutableText() {
        ChatModel model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ATTRIBUTION")))));
        ModelPlanner planner=new ModelPlanner(model);
        Request request=new Request("why",LocalDate.now(),null,"GMV",false);
        assertThat(planner.plan(request).intent()).isEqualTo(Intent.ATTRIBUTION);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("DROP TABLE biz_order")))));
        assertThatThrownBy(()->planner.plan(request)).hasMessage("MODEL_PLAN_REJECTED");
    }
}

