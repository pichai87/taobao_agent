package com.ecom.api;

import com.ecom.domain.Ports.*;
import static com.ecom.domain.Analysis.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RunController {
    private final RunUseCases runs;
    private final Knowledge knowledge;
    private final Planner planner;
    public RunController(RunUseCases runs,Knowledge knowledge,Planner planner) {this.runs=runs;this.knowledge=knowledge;this.planner=planner;}
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("headerName",token.getHeaderName(),"token",token.getToken());
    }
    @GetMapping("/capabilities") public Map<String,Object> capabilities() {
        return Map.of("mode",planner.mode(),"metric","GMV","intents",Intent.values(),"multiInstance",false,"vectorRag",false,
            "toolCalling",true,"maxModelRounds",8,"maxToolCalls",12,
            "multiAgent",Map.of("mode","LLM_MULTI_AGENT","maxModelRounds",16,"maxToolCalls",24,"maxHandoffs",6,
                "experts",List.of("IntentAgent","Supervisor","QueryAgent","AnalysisAgent","OpsAgent","OtherAgent")));
    }
    @GetMapping("/metrics") public List<Metric> metrics() {return knowledge.metrics();}
    @PostMapping("/runs") public Run submit(Principal user,@RequestHeader("Idempotency-Key") String key,@RequestBody Request request) {
        return runs.submit(user.getName(),key,request);
    }
    @GetMapping("/runs") public List<Run> list(Principal user) {return runs.list(user.getName());}
    @GetMapping("/runs/{id}") public Run get(Principal user,@PathVariable String id) {return runs.get(user.getName(),id);}
    @GetMapping("/runs/{id}/events") public List<Event> events(Principal user,@PathVariable String id,@RequestParam(defaultValue="0") long after) {
        return runs.events(user.getName(),id,after);
    }
    @PostMapping("/runs/{id}/approve") public Run approve(Principal user,@PathVariable String id) {return runs.approve(user.getName(),id);}
    @PostMapping("/runs/{id}/resume") public Run resume(Principal user,@PathVariable String id) {return runs.resume(user.getName(),id);}
    @PostMapping("/runs/{id}/cancel") public Run cancel(Principal user,@PathVariable String id) {return runs.cancel(user.getName(),id);}
}
