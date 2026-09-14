package com.ecom.domain;

import java.util.List;
import java.util.Optional;

public final class Requirements {
    private Requirements() {}
    public record Definition(String id,String layer,String name,String source,String criteria,
        boolean implemented,String extent,String verification,boolean rework,String notes) {}
    public record Review(boolean implemented,String extent,String verification,boolean rework,String notes,long version) {}
    public record Item(Definition definition,Review review) {}
    public interface Store {
        Optional<Review> find(String owner,String id);
        Review save(String owner,String id,Review review);
    }
    public interface Board {
        List<Item> list(String owner);
        Item save(String owner,String id,Review review);
    }
}
