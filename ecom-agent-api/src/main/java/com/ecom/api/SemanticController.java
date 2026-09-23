package com.ecom.api;

import com.ecom.domain.Semantic.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/semantic")
public class SemanticController {
    private final Workbench workbench;
    public SemanticController(Workbench workbench) {this.workbench=workbench;}
    @GetMapping("/model") public Model model() {return workbench.model();}
    @PostMapping("/plan") public Compiled plan(@RequestBody Query query) {return workbench.plan(query);}
    @PostMapping("/query") public List<Map<String,Object>> query(Principal user,@RequestBody Query query) {return workbench.query(user.getName(),query);}
    @PostMapping("/metrics") public MetricDef register(Principal user,@RequestBody MetricDef metric) {return workbench.register(user.getName(),metric);}
    @GetMapping("/knowledge") public List<KnowledgeHit> knowledge(@RequestParam String metric,@RequestParam(defaultValue="") String question) {return workbench.recall(metric,question);}
    @GetMapping("/memory") public Map<String,String> memory(Principal user) {return Map.of("summary",workbench.memory(user.getName()));}
    @PostMapping("/memory") public Map<String,String> remember(Principal user,@RequestBody Map<String,String> body) {
        workbench.remember(user.getName(),body.get("summary"));return memory(user);
    }
}
