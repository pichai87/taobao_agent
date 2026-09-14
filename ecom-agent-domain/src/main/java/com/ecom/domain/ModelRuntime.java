package com.ecom.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 临时模型会话：凭据不属于分析请求、报告或持久化检查点。 */
public final class ModelRuntime {
    private ModelRuntime() {}
    public static final String MODE = "LLM_TOOL_CALLING";
    public static final String MULTI_MODE = "LLM_MULTI_AGENT";
    public static boolean isModelMode(String mode) {return MODE.equals(mode) || MULTI_MODE.equals(mode);}
    public record Settings(String provider, String workspaceId, String model, String apiKey, boolean consent) {
        @Override public String toString() { return "ModelSettings[credentials=REDACTED]"; }
    }
    public record SessionInfo(String id, String provider, String model, String endpoint, Instant expiresAt) {}
    public record Tool(String name, String description, Map<String,Object> parameters) {}
    public record Call(String id, String name, Map<String,Object> arguments) {}
    public record Message(String role, String content, String callId, List<Call> calls) {
        public static Message text(String role,String content) {return new Message(role,content,null,List.of());}
    }
    public record Turn(String text, List<Call> calls, long totalTokens) {}
    public interface Sessions {
        SessionInfo create(String owner, Settings settings);
        SessionInfo require(String owner, String id);
        void clear(String owner, String id);
        String redact(String owner, String id, String text);
    }
    public interface Gateway {
        Turn complete(String owner, String sessionId, List<Message> messages, List<Tool> tools);
    }
}
