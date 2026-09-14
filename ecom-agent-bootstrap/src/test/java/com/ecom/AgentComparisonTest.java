package com.ecom;

import com.ecom.agent.collaboration.AgentContracts.Goal;
import com.ecom.domain.Analysis.*;
import com.ecom.domain.ModelRuntime.Message;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Same database, dates, tools and scripted decisions; measures orchestration, not real-model accuracy. */
@SpringBootTest @ActiveProfiles("test")
class AgentComparisonTest extends AgentTestFixture {
    @Test void compareBothArchitecturesWithoutPresupposingMultiAgentWins() throws Exception {
        List<Map<String,Object>> cases=new ArrayList<>();
        for(Goal goal:List.of(Goal.QUERY,Goal.COMPARE,Goal.ATTRIBUTION,Goal.TREND,Goal.TOP_N,Goal.DEFINITION)) {
            String question=switch(goal) {case QUERY->"GMV";case COMPARE->"GMV 对比";case ATTRIBUTION->"GMV 为什么下降";case TREND->"GMV 七天趋势";case TOP_N->"GMV 排名";default->"GMV 是什么";};
            Run single=create(question,false),multi=create(question,true);
            FixtureModel baseline=new FixtureModel(goal),experts=new FixtureModel(goal);
            execute(single,baseline);execute(multi,experts);
            assertThat(finished(single).status()).isEqualTo(Status.SUCCEEDED);assertThat(finished(multi).status()).isEqualTo(Status.SUCCEEDED);
            assertThat(finished(multi).report().data()).isEqualTo(finished(single).report().data());
            cases.add(Map.of("case",goal.name(),"sameStructuredResult",true,"single",metrics(single,baseline),"multi",metrics(multi,experts)));
        }
        Run single=create("GMV 为什么下降",false),multi=create("GMV 为什么下降",true);
        FixtureModel exposed=new FixtureModel(Goal.ATTRIBUTION),isolated=new FixtureModel(Goal.ATTRIBUTION);exposed.poison=true;isolated.poison=true;
        execute(single,exposed);execute(multi,isolated);
        boolean singleSees=containsMarker(exposed,"single_agent"),analysisSees=containsMarker(isolated,"analysis_agent");
        assertThat(singleSees).isTrue();assertThat(analysisSees).isFalse();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("generatedAt",Instant.now().toString());report.put("method","DETERMINISTIC_PROTOCOL_FIXTURE_REAL_H2");
        report.put("realProviderCalled",false);report.put("cases",cases);
        report.put("contextIsolation",Map.of("singleSeesPrivateQueryProse",singleSees,"analysisSeesPrivateQueryProse",analysisSees,
            "limitation","Only raw expert prose is isolated. Structured evidence may still contain untrusted text; this is not proof against all prompt injection."));
        report.put("conclusion","Both pass the six fixed numeric/definition fixtures. Multi-agent narrows per-expert tools and isolates raw query dialogue, but usually adds model round trips. Real-model accuracy, tokens, cost and latency are NOT measured.");
        Path path=Path.of("target","surefire-reports","agent-comparison.json");Files.createDirectories(path.getParent());
        Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    private boolean containsMarker(FixtureModel model,String actor) {return model.observations.stream().filter(o->o.actor().equals(actor)).flatMap(o->o.messages().stream()).map(Message::content).anyMatch(s->s.contains(FixtureModel.PRIVATE_MARKER));}
    private Map<String,Object> metrics(Run run,FixtureModel model) {
        var events=store.events(run.id(),0);
        return Map.of("modelRequests",model.observations.size(),"maxToolsPerRequest",model.observations.stream().mapToInt(o->o.tools().size()).max().orElse(0),
            "toolMenuEntriesAcrossRequests",model.observations.stream().mapToInt(o->o.tools().size()).sum(),
            "serializedInputBytes",model.observations.stream().mapToInt(Observation::inputBytes).sum(),
            "queryEvents",events.stream().filter(e->e.node().equals("query") && e.status().equals("SUCCEEDED")).count(),
            "handoffDispatches",events.stream().filter(e->e.node().equals("handoff") && e.status().equals("STARTED")).count());
    }
}
