package com.ecom.api;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Lineage;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/lineage")
public class LineageController {
    private final Lineage.Service service;
    public LineageController(Lineage.Service service) {this.service=service;}
    public record Input(String sql) {}
    @GetMapping("/schema") public Map<String,List<String>> schema() {return service.schema();}
    @GetMapping("/model") public Lineage.ModelSource model() {return service.model();}
    @PostMapping("/analyze") public Lineage.Result analyze(@RequestBody Input input) {
        if(input==null || input.sql()==null) throw new BusinessException("INVALID_REQUEST");
        return service.analyze(input.sql());
    }
}
