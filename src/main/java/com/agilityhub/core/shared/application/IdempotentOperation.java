package com.agilityhub.core.shared.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Lets a retrying use case commit its response and effects in the same transaction. */
public final class IdempotentOperation {
    private record Operation(Runnable lock,BiConsumer<Integer,byte[]> complete,String reference,AtomicBoolean released) { }
    private static final ThreadLocal<Operation> CURRENT=new ThreadLocal<>();
    /** CONVENCIONS_API §7: a key lives 24 h (the retention of the idempotency records); after that the same key is a new request. */
    public static final java.time.Duration KEY_LIFETIME=java.time.Duration.ofHours(24);
    private IdempotentOperation() { }
    public static Scope open(Runnable lock,BiConsumer<Integer,byte[]> complete) { return open(lock,complete,null); }
    /**
     * E5-T29 round 2 (CONVENCIONS_API §7, ruling E75): a keyed request whose answer the filter stores in the request's own
     * transaction. The scope only names the request: a use case whose effects commit outside that transaction (a keyed
     * `POST /jobs/{name}/trigger`) keeps the reference with them, and the retry after a lost answer finds and answers them.
     * {@link #lock()} and {@link #complete} do nothing here, as outside any scope.
     */
    public static Scope referenced(String reference) { return open(() -> { },(status,response) -> { },reference); }
    /**
     * {@code reference} names the request (its scope, key and body) whatever record the key has now: a use case whose answer
     * could not be stored finds, on the retry with the same key, what the first attempt committed (E5-T28, CONVENCIONS_API §7).
     */
    public static Scope open(Runnable lock,BiConsumer<Integer,byte[]> complete,String reference) {
        var previous=CURRENT.get(); var operation=new Operation(lock,complete,reference,new AtomicBoolean()); CURRENT.set(operation);
        return new Scope() {
            @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
            @Override public boolean released() { return operation.released().get(); }
        };
    }
    public static void lock() { var operation=CURRENT.get(); if(operation!=null) operation.lock().run(); }
    public static void complete(int status,byte[] response) { var operation=CURRENT.get(); if(operation!=null) operation.complete().accept(status,response); }
    /** The reference of the current keyed request (a digest, never the key itself), or null outside one. */
    public static String reference() { var operation=CURRENT.get(); return operation==null?null:operation.reference(); }
    /**
     * The failure that follows is not a business outcome (E5-T10: a PAY_TO_BOOK checkout that could not be opened):
     * the key is released even when the response is a 409/422 that would otherwise be replayed.
     */
    public static void release() { var operation=CURRENT.get(); if(operation!=null) operation.released().set(true); }
    public interface Scope extends AutoCloseable {
        @Override void close();
        /** True once {@link #release()} ran inside this scope. */
        boolean released();
    }
}
