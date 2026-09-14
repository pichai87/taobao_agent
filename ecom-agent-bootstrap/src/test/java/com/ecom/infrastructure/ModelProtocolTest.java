package com.ecom.infrastructure;

import com.ecom.domain.ModelRuntime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModelProtocolTest {
    @Test void endpointCannotBeChangedToArbitraryHosts() {
        try(var sessions=new MemoryModelSessions()) {
            assertThatThrownBy(()->sessions.create("a",new Settings("http://localhost:9000","","qwen-plus","sk-test-not-real-key",true)))
                .hasMessage("MODEL_PROVIDER_NOT_ALLOWED");
            assertThatThrownBy(()->sessions.create("a",new Settings("BAILIAN_BEIJING","x.evil.example/","qwen-plus","sk-test-not-real-key",true)))
                .hasMessage("INVALID_MODEL_SETTINGS");
            var valid=sessions.create("a",new Settings("BAILIAN_BEIJING","workspace-1","qwen-plus","sk-test-not-real-key",true));
            assertThat(valid.endpoint()).isEqualTo("https://workspace-1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions");
        }
    }
    @Test void secretsExpireAndNeverAppearInSettingsToString() {
        final Instant[] now={Instant.parse("2026-09-14T00:00:00Z")};
        Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now[0];}
        };
        var settings=new Settings("BAILIAN_BEIJING","","qwen-plus","sk-test-not-real-key",true);
        assertThat(settings.toString()).doesNotContain(settings.apiKey());
        try(var sessions=new MemoryModelSessions(clock)) {
            var info=sessions.create("a",settings);
            now[0]=now[0].plusSeconds(1801);
            assertThatThrownBy(()->sessions.require("a",info.id())).hasMessage("MODEL_SESSION_EXPIRED");
        }
    }
    @Test void toolMessageWireFormatIncludesIdsAndSerializedArguments() throws Exception {
        try(var sessions=new MemoryModelSessions()) {
            var gateway=new BailianToolGateway(sessions,new ObjectMapper());
            var payload=gateway.payload("qwen-plus",List.of(new Message("assistant","",null,
                List.of(new Call("call-1","query_daily",Map.of("period","current")))),new Message("tool","[]","call-1",List.of())),List.of());
            String wire=new ObjectMapper().writeValueAsString(payload);
            assertThat(wire).contains("tool_call_id","call-1","tool_choice","arguments").doesNotContain("apiKey","Authorization");
            assertThat(payload).containsEntry("enable_thinking",false).containsEntry("max_tokens",1024);
        }
    }
    @Test void providerResponseMustContainValidStructuredToolCalls() {
        try(var sessions=new MemoryModelSessions()) {
            var gateway=new BailianToolGateway(sessions,new ObjectMapper());
            String good="""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"query_daily","arguments":"{\\"period\\":\\"current\\"}"}}]}}],"usage":{"total_tokens":12}}
                """;
            var turn=gateway.decode(good.getBytes(StandardCharsets.UTF_8));
            assertThat(turn.calls().getFirst().arguments()).containsEntry("period","current");
            assertThat(turn.totalTokens()).isEqualTo(12);
            assertThatThrownBy(()->gateway.decode("{\"choices\":[]}".getBytes(StandardCharsets.UTF_8))).hasMessage("MODEL_RESPONSE_INVALID");
            assertThatThrownBy(()->gateway.decode(good.replace("tool_calls\",\"message","length\",\"message").getBytes(StandardCharsets.UTF_8))).hasMessage("MODEL_RESPONSE_INVALID");
        }
    }
}
