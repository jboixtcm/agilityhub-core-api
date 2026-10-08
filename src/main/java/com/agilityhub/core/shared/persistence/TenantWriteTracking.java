package com.agilityhub.core.shared.persistence;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import com.agilityhub.core.shared.application.TenantWriteFence;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * R-18-17: every template mutation of club data shares reset's counter document lock, including custom
 * updates/deletes and privileged workers without a TenantContext. An active registration protects the data
 * transaction until its outcome advances the counter; maintenance holds the same counter lock throughout.
 */
@Aspect
@Component
public class TenantWriteTracking {
    private static final String COUNTERS = "tenant_write_counters";
    // Maintenance coordination is not application data. In particular, releasing the import lease must
    // not invalidate the checkpoint just committed by that import.
    private static final Set<String> COORDINATION = Set.of(COUNTERS, "migration_write_locks", "migration_reset_guards");
    private final TransactionTemplate transactions;
    private final ObjectProvider<TenantWriteFence> writes;

    public TenantWriteTracking(PlatformTransactionManager manager, ObjectProvider<TenantWriteFence> writes) {
        transactions = new TransactionTemplate(manager); this.writes = writes;
    }

    @Around("execution(* org.springframework.data.mongodb.core.MongoOperations+.insert(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.insertAll(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.save(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.upsert(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.updateFirst(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.updateMulti(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.findAndModify(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.findAndReplace(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.findAndRemove(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.findAllAndRemove(..)) || "
            + "execution(* org.springframework.data.mongodb.core.MongoOperations+.remove(..))")
    public Object write(ProceedingJoinPoint call) {
        MongoTemplate mongo = (MongoTemplate) call.getTarget();
        Object[] args = call.getArgs();
        String collection = collection(mongo, args);
        if (COORDINATION.contains(collection)) { return proceed(call); }
        return transactions.execute(status -> {
            Set<String> tenants = tenants(mongo, collection, call.getSignature().getName(), args);
            writes.getObject().begin(tenants);
            Object result = proceed(call);
            if (changed(result, args)) {
                // findAnd* returns the actual affected rows, even for a dispatcher selecting across clubs.
                if (result != null && call.getSignature().getName().startsWith("find")) {
                    tenants = new TreeSet<>(); addEntity(mongo, tenants, result);
                }
                for (String club : tenants) { writes.getObject().written(club); }
            }
            return result;
        });
    }

    private static Object proceed(ProceedingJoinPoint call) {
        try { return call.proceed(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Mongo mutation failed", failure); }
    }

    private static boolean changed(Object result, Object[] args) {
        if (result instanceof UpdateResult update) { return update.getModifiedCount() > 0 || update.getUpsertedId() != null; }
        if (result instanceof DeleteResult delete) { return delete.getDeletedCount() > 0; }
        if (result instanceof Collection<?> rows) { return !rows.isEmpty(); }
        // findAnd* upserts may return the old value (null) even though a new row was inserted.
        for (Object arg : args) {
            if (arg instanceof FindAndModifyOptions options && options.isUpsert()) { return true; }
            if (arg instanceof FindAndReplaceOptions options && options.isUpsert()) { return true; }
        }
        return result != null;
    }

    private static String collection(MongoTemplate mongo, Object[] args) {
        for (int i = args.length - 1; i > 0; i--) {
            if (args[i] instanceof String name) { return name; }
        }
        for (Object arg : args) { if (arg instanceof Class<?> type) { return mongo.getCollectionName(type); } }
        Object entity = args[0] instanceof Query ? args[1] : args[0];
        if (entity instanceof Collection<?>) { return ""; } // insertAll/typed batch: each document supplies its tenant.
        return mongo.getCollectionName(entity.getClass());
    }

    private static Set<String> tenants(MongoTemplate mongo, String collection, String operation, Object[] args) {
        var tenants = new TreeSet<String>();
        if (args[0] instanceof Query query) {
            Object club = query.getQueryObject().get("clubId");
            if (club instanceof String id) { tenants.add(id); }
            else {
                // Projection only: no personal data is copied into the counter, even for an unscoped worker.
                Query ids = Query.of(query); ids.fields().include("clubId");
                // Total-order single-row claims read the same first row in this transaction's snapshot.
                // Registering every candidate would make another club's maintenance block the dispatcher.
                if (Set.of("findAndModify", "findAndReplace", "findAndRemove").contains(operation)
                        && query.getSortObject().containsKey("_id")) { ids.limit(1); }
                mongo.find(ids, Document.class, collection).forEach(doc -> add(tenants, doc));
            }
            if (args.length > 1 && args[1] instanceof UpdateDefinition update) {
                for (String operator : List.of("$set", "$setOnInsert")) {
                    Object fields = update.getUpdateObject().get(operator);
                    if (fields instanceof Map<?, ?> values) { add(tenants, values); }
                }
            } else if (args.length > 1 && !(args[1] instanceof Class<?>) && !(args[1] instanceof String)) {
                addEntity(mongo, tenants, args[1]);
            }
        } else { addEntity(mongo, tenants, args[0]); }
        return tenants;
    }

    private static void addEntity(MongoTemplate mongo, Set<String> tenants, Object entity) {
        if (entity instanceof Collection<?> rows) { rows.forEach(row -> addEntity(mongo, tenants, row)); }
        else {
            var document = new Document(); mongo.getConverter().write(entity, document); add(tenants, document);
        }
    }

    private static void add(Set<String> tenants, Map<?, ?> document) {
        if (document.get("clubId") instanceof String club && !club.isBlank()) { tenants.add(club); }
    }
}
