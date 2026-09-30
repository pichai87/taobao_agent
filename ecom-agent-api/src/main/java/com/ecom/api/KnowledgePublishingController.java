package com.ecom.api;

import com.ecom.domain.BusinessException;
import com.ecom.domain.KnowledgePublishing.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Authentication and CSRF are inherited from the existing API security policy. */
@RestController
@RequestMapping("/api/knowledge/drafts")
public class KnowledgePublishingController {
    private final Service publishing;
    public KnowledgePublishingController(Service publishing) { this.publishing = publishing; }

    @GetMapping public List<Draft> list(Principal user) { return publishing.list(user.getName()); }
    @PostMapping public Draft create(Principal user, @RequestBody Content content) { return publishing.create(user.getName(), content); }
    @GetMapping("/{id}") public Draft get(Principal user, @PathVariable String id) { return publishing.get(user.getName(), id); }
    @PutMapping("/{id}") public Draft edit(Principal user, @PathVariable String id, @RequestBody Edit edit) { return publishing.edit(user.getName(), id, edit); }
    @PostMapping("/{id}/publish") public Draft publish(Principal user, @PathVariable String id, @RequestBody Review review) { return publishing.publish(user.getName(), id, review); }
    @PostMapping("/{id}/reject") public Draft reject(Principal user, @PathVariable String id, @RequestBody Review review) { return publishing.reject(user.getName(), id, review); }
    @PostMapping("/{id}/revisions") public Draft revise(Principal user, @PathVariable String id, @RequestBody Revision revision) { return publishing.revise(user.getName(), id, revision); }
    @GetMapping("/{id}/audit") public List<Audit> audit(Principal user, @PathVariable String id) { return publishing.audit(user.getName(), id); }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String,String>> failure(BusinessException error) {
        HttpStatus status = switch (error.code()) {
            case "KNOWLEDGE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "KNOWLEDGE_VERSION_CONFLICT", "KNOWLEDGE_STATE_CONFLICT", "KNOWLEDGE_REVISION_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", error.code()));
    }
}
