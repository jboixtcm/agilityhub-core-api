package com.agilityhub.core.support;

import java.util.Collection;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * E11-T10 (INC-38): counts the reads (`find`, `aggregate`, `count`, `getMore`) the database served on the given collections while
 * an action ran, with Mongo's own profiler (level 2), so a test shows that a list's reads do not grow with its rows. The ITs
 * run one at a time (scheduling off), so nothing else reads meanwhile.
 */
public final class MongoReads {
    private MongoReads() { }

    @FunctionalInterface public interface Action { void run() throws Exception; }

    /** The profiled operations of {@code action} on {@code collections}: one document per read, with `op` and `ns`. */
    public static List<Document> during(MongoTemplate mongo, Collection<String> collections, Action action) throws Exception {
        var database = mongo.getDb();
        database.runCommand(new Document("profile", 0));
        database.getCollection("system.profile").drop();
        database.runCommand(new Document("profile", 2));
        try { action.run(); }
        finally { database.runCommand(new Document("profile", 0)); }
        var names = collections.stream().map(name -> database.getName() + "." + name).toList();
        return mongo.find(Query.query(Criteria.where("ns").in(names).and("op").in("query", "command", "getmore")), Document.class, "system.profile")
                .stream().filter(MongoReads::read).toList();
    }

    /** The number of reads of {@code action} on {@code collections}. */
    public static long count(MongoTemplate mongo, Collection<String> collections, Action action) throws Exception {
        return during(mongo, collections, action).size();
    }

    private static boolean read(Document operation) {
        if (!"command".equals(operation.getString("op"))) { return true; }
        var command = operation.get("command", Document.class);
        return command != null && (command.containsKey("aggregate") || command.containsKey("count") || command.containsKey("distinct"));
    }
}
