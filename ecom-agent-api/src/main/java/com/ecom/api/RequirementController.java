package com.ecom.api;

import com.ecom.domain.Requirements.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/requirements")
public class RequirementController {
    private final Board board;
    public RequirementController(Board board) {this.board=board;}
    @GetMapping public Map<String,Object> list(Principal user) {
        return Map.of("originalUrl","https://mp.weixin.qq.com/s/BpsscOnYq-DWsb_DrID8hA",
            "referenceUrl","https://www.aixq.cc/67378.html",
            "sourceNote","已核对微信原文架构章节，保留对应转载链接；本目录是原文核心能力的 Java 适配验收清单，并单列本项目新增要求。不是原系统完成度认证。",
            "catalogVersion","2026-09-14-v2-multi-agent","items",board.list(user.getName()));
    }
    @PostMapping("/{id}") public Item save(Principal user,@PathVariable String id,@RequestBody Review review) {
        return board.save(user.getName(),id,review);
    }
}
