package com.agilityhub.core.shared.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Lets a retrying use case commit its response and effects in the same transaction. */
public final class IdempotentOperation {
    private record Operation(Runnable lock,BiConsumer<Integer,byte[]> complete,String reference,AtomicBoolean released) { }
    private static final ThreadLocal<Operation> CURRENT=new ThreadLocal<>();
    private IdempotentOperation() { }
    public static Scope open(Runnable lock,BiConsumer<Integer,byte[]> complete) { return open(lock,complete,null); }
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
