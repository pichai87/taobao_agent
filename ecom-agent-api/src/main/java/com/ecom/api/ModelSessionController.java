package com.ecom.api;

import com.ecom.domain.BusinessException;
import com.ecom.domain.ModelRuntime.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/model-session")
public class ModelSessionController {
    private static final String ATTRIBUTE="model.session.id";
    private final Sessions sessions;
    public ModelSessionController(Sessions sessions) {this.sessions=sessions;}
    @GetMapping public Map<String,Object> status(Principal user,HttpSession session) {
        String id=(String)session.getAttribute(ATTRIBUTE);
        if(id==null) return Map.of("configured",false);
        try {return Map.of("configured",true,"session",sessions.require(user.getName(),id));}
        catch(BusinessException expired) {session.removeAttribute(ATTRIBUTE);return Map.of("configured",false);}
    }
    @PostMapping public SessionInfo configure(Principal user,HttpServletRequest request,@RequestBody Settings settings) {
        // 远程部署必须用 HTTPS；不能把密钥通过明文 LAN 连接传输。
        if(!request.isSecure() && !java.util.Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))
            throw new BusinessException("MODEL_HTTPS_REQUIRED");
        HttpSession session=request.getSession();
        SessionInfo info=sessions.create(user.getName(),settings);
        String old=(String)session.getAttribute(ATTRIBUTE);
        if(old!=null) sessions.clear(user.getName(),old);
        session.setAttribute(ATTRIBUTE,info.id());
        return info;
    }
    @PostMapping("/clear") public Map<String,Boolean> clear(Principal user,HttpSession session) {
        String id=(String)session.getAttribute(ATTRIBUTE);
        if(id!=null) sessions.clear(user.getName(),id);
        session.removeAttribute(ATTRIBUTE);
        return Map.of("configured",false);
    }
}
