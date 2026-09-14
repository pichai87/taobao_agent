package com.ecom.application;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Requirements.*;
import java.util.*;

/** 代码实现程度与验证程度是独立字段，不能以勾选代替真实验收。 */
public final class RequirementService implements Board {
    private final List<Definition> catalog;
    private final Store store;
    public RequirementService(List<Definition> catalog,Store store) {
        this.catalog=List.copyOf(catalog);this.store=store;
        if(catalog.stream().map(Definition::id).distinct().count()!=catalog.size()) throw new IllegalArgumentException("Duplicate requirement id");
    }
    public List<Item> list(String owner) {return catalog.stream().map(d->new Item(d,review(owner,d))).toList();}
    private Review review(String owner,Definition d) {
        return store.find(owner,d.id()).orElseGet(()->new Review(d.implemented(),d.extent(),d.verification(),d.rework(),d.notes(),0));
    }
    public Item save(String owner,String id,Review review) {
        Definition definition=catalog.stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(()->new BusinessException("REQUIREMENT_NOT_FOUND"));
        if(review==null || review.extent()==null || review.verification()==null || review.notes()==null ||
            review.notes().isBlank() || review.notes().length()>3000 || review.version()<0 ||
            !Set.of("NONE","PARTIAL","IMPLEMENTED").contains(review.extent()) ||
            !Set.of("NOT_TESTED","AUTOMATED","LOCAL_E2E","LIVE_PROVIDER").contains(review.verification()) ||
            review.implemented()!=review.extent().equals("IMPLEMENTED")) throw new BusinessException("INVALID_REQUIREMENT_REVIEW");
        if(java.util.regex.Pattern.compile("sk-[A-Za-z0-9_-]{12,}").matcher(review.notes()).find())
            throw new BusinessException("REVIEW_CONTAINS_SECRET");
        return new Item(definition,store.save(owner,id,review));
    }
}
