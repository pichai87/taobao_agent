package com.ecom.infrastructure;

import com.ecom.domain.BusinessException;
import com.ecom.domain.ModelRuntime.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 单实例、30 分钟临时凭据库。只存内存；清除/过期后拒绝后续模型调用。 */
public final class MemoryModelSessions implements Sessions, AutoCloseable {
    private static final int MAX_SESSIONS=128;
    private final Map<String,Entry> entries=new HashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService cleaner;
    private static final class Entry {
        final String owner;
        final SessionInfo info;
        final char[] key;
        Entry(String owner,SessionInfo info,String key) {this.owner=owner;this.info=info;this.key=key.toCharArray();}
        void destroy() {Arrays.fill(key,'\0');}
        @Override public String toString() {return "ModelSession[REDACTED]";}
    }
    public MemoryModelSessions() {this(Clock.systemUTC());}
    public MemoryModelSessions(Clock clock) {
        this.clock=clock;
        cleaner=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-key-expiry");t.setDaemon(true);return t;});
        cleaner.scheduleWithFixedDelay(this::purge,30,30,TimeUnit.SECONDS);
    }
    public synchronized SessionInfo create(String owner,Settings settings) {
        purge();
        if(settings==null || !settings.consent()) throw new BusinessException("MODEL_CONSENT_REQUIRED");
        String key=settings.apiKey(), model=settings.model(), workspace=settings.workspaceId();
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,256}") || model==null || !model.matches("qwen-[a-zA-Z0-9._-]{1,80}"))
            throw new BusinessException("INVALID_MODEL_SETTINGS");
        if(workspace==null) workspace="";
        if(!workspace.isEmpty() && !workspace.matches("[a-zA-Z0-9-]{1,64}")) throw new BusinessException("INVALID_MODEL_SETTINGS");
        String host=switch(settings.provider()==null?"":settings.provider()) {
            case "BAILIAN_BEIJING" -> workspace.isEmpty()?"dashscope.aliyuncs.com":workspace+".cn-beijing.maas.aliyuncs.com";
            case "BAILIAN_SINGAPORE" -> workspace.isEmpty()?"dashscope-intl.aliyuncs.com":workspace+".ap-southeast-1.maas.aliyuncs.com";
            default -> throw new BusinessException("MODEL_PROVIDER_NOT_ALLOWED");
        };
        if(entries.size()>=MAX_SESSIONS) throw new BusinessException("MODEL_SESSION_CAPACITY");
        var info=new SessionInfo(UUID.randomUUID().toString(),settings.provider(),model,
            "https://"+host+"/compatible-mode/v1/chat/completions",clock.instant().plus(Duration.ofMinutes(30)));
        entries.put(info.id(),new Entry(owner,info,key));
        return info;
    }
    public synchronized SessionInfo require(String owner,String id) {return entry(owner,id).info;}
    // 仅基础设施网关需要读密钥，领域和控制器没有此接口。
    synchronized String authorization(String owner,String id) {return "Bearer "+new String(entry(owner,id).key);}
    public synchronized String redact(String owner,String id,String text) {
        if(text==null) return "";
        return text.replace(new String(entry(owner,id).key),"[REDACTED]").replaceAll("sk-[A-Za-z0-9_-]{12,}","[REDACTED]");
    }
    public synchronized void clear(String owner,String id) {
        Entry entry=entries.get(id);
        if(entry!=null && entry.owner.equals(owner)) {entries.remove(id);entry.destroy();}
    }
    private Entry entry(String owner,String id) {
        purge();
        Entry entry=entries.get(id);
        if(entry==null || !entry.owner.equals(owner)) throw new BusinessException("MODEL_SESSION_EXPIRED");
        return entry;
    }
    private synchronized void purge() {
        entries.values().removeIf(entry->{
            if(!clock.instant().isBefore(entry.info.expiresAt())) {entry.destroy();return true;}return false;
        });
    }
    public synchronized void close() {cleaner.shutdownNow();entries.values().forEach(Entry::destroy);entries.clear();}
}
