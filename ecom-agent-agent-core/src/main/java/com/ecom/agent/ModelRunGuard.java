package com.ecom.agent;

import com.ecom.domain.BusinessException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared single-instance quota for both single-agent and multi-agent tasks. */
public final class ModelRunGuard {
    private static final Set<String> OWNERS=ConcurrentHashMap.newKeySet();
    private ModelRunGuard() {}
    public static void acquire(String owner) {if(!OWNERS.add(owner)) throw new BusinessException("MODEL_BUSY");}
    public static void release(String owner) {OWNERS.remove(owner);}
}
