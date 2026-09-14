package com.ecom.infrastructure;

import com.ecom.domain.BusinessException;
import com.ecom.domain.ModelRuntime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 百炼兼容接口的显式 tool_calls 协议适配器。无自动重试、无重定向、无任意 URL。 */
public final class BailianToolGateway implements Gateway {
    private final MemoryModelSessions sessions;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public BailianToolGateway(MemoryModelSessions sessions,ObjectMapper json) {this.sessions=sessions;this.json=json;}
    public Turn complete(String owner,String sessionId,List<Message> messages,List<Tool> tools) {
        var info=sessions.require(owner,sessionId);
        try {
            byte[] payload=json.writeValueAsBytes(payload(info.model(),messages,tools));
            if(payload.length>100_000) throw new BusinessException("MODEL_CONTEXT_LIMIT");
            var request=HttpRequest.newBuilder(URI.create(info.endpoint())).timeout(Duration.ofSeconds(25))
                .header("Authorization",sessions.authorization(owner,sessionId)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            // 限制实际接收字节数；到达上限立即取消订阅，不能把任意大响应读进内存。
            var pending=client.sendAsync(request,ignored->new LimitedBodySubscriber(256_000));
            HttpResponse<byte[]> response;
            try {response=pending.get(25,TimeUnit.SECONDS);}
            catch(TimeoutException e) {pending.cancel(true);throw new BusinessException("MODEL_TIMEOUT");}
            catch(InterruptedException e) {pending.cancel(true);Thread.currentThread().interrupt();throw new BusinessException("RUN_STOPPED");}
            if(response.statusCode()!=200) throw new BusinessException(switch(response.statusCode()) {
                case 401,403 -> "MODEL_AUTH_FAILED";
                case 429 -> "MODEL_RATE_LIMITED";
                case 400,404 -> "MODEL_REQUEST_REJECTED";
                default -> "MODEL_PROVIDER_FAILED";
            });
            return decode(response.body());
        } catch(BusinessException e) {throw e;}
        catch(ExecutionException e) {
            if(e.getCause() instanceof HttpTimeoutException) throw new BusinessException("MODEL_TIMEOUT");
            throw new BusinessException("MODEL_CONNECTION_FAILED");
        }
        // 不保留供应商原始错误、异常 cause、请求头或响应体，防止密钥进入日志。
        catch(Exception e) {throw new BusinessException("MODEL_CONNECTION_FAILED");}
    }
    Map<String,Object> payload(String model,List<Message> messages,List<Tool> tools) throws Exception {
        var wire=new ArrayList<Map<String,Object>>();
        for(Message message:messages) {
            var item=new LinkedHashMap<String,Object>();item.put("role",message.role());item.put("content",message.content());
            if(message.callId()!=null) item.put("tool_call_id",message.callId());
            if(!message.calls().isEmpty()) {
                var calls=new ArrayList<Map<String,Object>>();
                for(Call call:message.calls()) calls.add(Map.of("id",call.id(),"type","function","function",
                    Map.of("name",call.name(),"arguments",json.writeValueAsString(call.arguments()))));
                item.put("tool_calls",calls);
            }
            wire.add(item);
        }
        return Map.of("model",model,"messages",wire,"tools",tools.stream().map(tool->Map.of("type","function",
            "function",Map.of("name",tool.name(),"description",tool.description(),"parameters",tool.parameters()))).toList(),
            "tool_choice","auto","enable_thinking",false,"max_tokens",1024,"stream",false);
    }
    Turn decode(byte[] bytes) {
        try {
            JsonNode root=json.readTree(bytes), choice=root.path("choices").path(0), message=choice.path("message");
            if(!message.isObject() || "length".equals(choice.path("finish_reason").asText()))
                throw new BusinessException("MODEL_RESPONSE_INVALID");
            String text=message.path("content").asText("");
            if(text.length()>12000) throw new BusinessException("MODEL_RESPONSE_INVALID");
            var calls=new ArrayList<Call>();
            JsonNode rawCalls=message.path("tool_calls");
            if(!rawCalls.isMissingNode() && !rawCalls.isNull() && !rawCalls.isArray()) throw new BusinessException("MODEL_RESPONSE_INVALID");
            for(JsonNode raw:rawCalls) {
                String id=raw.path("id").asText(), name=raw.path("function").path("name").asText();
                String args=raw.path("function").path("arguments").asText();
                if(!"function".equals(raw.path("type").asText()) || !id.matches("[a-zA-Z0-9_-]{1,128}") ||
                    !name.matches("[a-z_]{1,64}") || args.length()>2048 || calls.size()>=4)
                    throw new BusinessException("MODEL_RESPONSE_INVALID");
                JsonNode parsed=json.readTree(args);
                if(parsed==null || !parsed.isObject()) throw new BusinessException("MODEL_RESPONSE_INVALID");
                calls.add(new Call(id,name,json.convertValue(parsed,new TypeReference<Map<String,Object>>(){})));
            }
            return new Turn(text,List.copyOf(calls),Math.max(0,root.path("usage").path("total_tokens").asLong(0)));
        } catch(BusinessException e) {throw e;}
        catch(Exception e) {throw new BusinessException("MODEL_RESPONSE_INVALID");}
    }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) {this.limit=limit;}
        public CompletionStage<byte[]> getBody() {return result;}
        public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;subscription.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>limit-output.size()) {subscription.cancel();result.completeExceptionally(new IllegalStateException("RESPONSE_TOO_LARGE"));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);output.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {result.completeExceptionally(error);}
        public void onComplete() {result.complete(output.toByteArray());}
    }
}
