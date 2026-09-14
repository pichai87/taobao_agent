package com.ecom.agent.collaboration;

import com.ecom.domain.Analysis.*;
import java.time.LocalDate;
import java.util.List;

/** Typed handoffs carry database evidence, never another agent's raw conversation. */
public final class AgentContracts {
    private AgentContracts() {}
    public enum Goal { QUERY, COMPARE, ATTRIBUTION, TREND, TOP_N, DEFINITION, OPS, OTHER }
    public enum Scope { DEFINITION, CURRENT, BOTH, TREND }
    public record Decision(Goal goal,double confidence,String source) {}
    public record DataRequest(Scope scope) {}
    public record EvidencePacket(String id,String runId,LocalDate date,LocalDate compareDate,Scope scope,
                                 List<Daily> rows,List<Daily> previous,List<Evidence> evidence,List<String> sql) {
        public EvidencePacket {rows=List.copyOf(rows);previous=List.copyOf(previous);evidence=List.copyOf(evidence);sql=List.copyOf(sql);}
    }
    public record ExpertResult(Goal goal,Result data,List<Evidence> evidence,List<String> sql,String answer) {
        public ExpertResult {evidence=List.copyOf(evidence);sql=List.copyOf(sql);}
    }
    @FunctionalInterface public interface DataDelegate {EvidencePacket request(DataRequest request) throws Exception;}
}
