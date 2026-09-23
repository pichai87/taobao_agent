package com.ecom.api;

import com.ecom.domain.Analysis.*;
import com.ecom.domain.Ports.RunUseCases;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RestController
public class EventStreamController {
    private final RunUseCases runs;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    private final Semaphore connections=new Semaphore(32);
    public EventStreamController(RunUseCases runs) {this.runs=runs;}
    @GetMapping(value="/api/runs/{id}/stream",produces="text/event-stream")
    public SseEmitter stream(Principal user,@PathVariable String id,
            @RequestHeader(value="Last-Event-ID",required=false) Long lastId,
            @RequestParam(defaultValue="0") long after) {
        runs.get(user.getName(),id);
        if(!connections.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        SseEmitter emitter=new SseEmitter(65_000L);
        AtomicLong cursor=new AtomicLong(lastId==null?after:lastId);
        AtomicBoolean closed=new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> future=new AtomicReference<>();
        Runnable release=()-> {
            if(closed.compareAndSet(false,true)) connections.release();
            ScheduledFuture<?> task=future.get();
            if(task!=null) task.cancel(false);
        };
        emitter.onCompletion(release);
        emitter.onTimeout(()-> {release.run();emitter.complete();});
        emitter.onError(error->release.run());
        ScheduledFuture<?> task=scheduler.scheduleAtFixedRate(()-> {
            if(closed.get()) return;
            try {
                // 先读终态，再读事件，避免终态已经可见但最后一批事件尚未读取。
                Run run=runs.get(user.getName(),id);
                var page=runs.events(user.getName(),id,cursor.get());
                for(Event event:page) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("node").data(event));
                    cursor.set(event.sequence());
                }
                // 一页恰好装满时先继续排空；否则客户端看见终态会中断并漏掉后页。
                if(page.size()>=1000) return;
                emitter.send(SseEmitter.event().name("status").data(run.status()));
                if(Set.of(Status.SUCCEEDED,Status.FAILED,Status.CANCELLED,Status.INTERRUPTED,Status.WAITING_FOR_REVIEW).contains(run.status())) {
                    release.run();emitter.complete();
                }
            } catch(Exception e) {release.run();emitter.completeWithError(e);}
        },100,500,TimeUnit.MILLISECONDS);
        future.set(task);
        if(closed.get()) task.cancel(false);
        return emitter;
    }
    @PreDestroy public void close() {scheduler.shutdownNow();}
}
