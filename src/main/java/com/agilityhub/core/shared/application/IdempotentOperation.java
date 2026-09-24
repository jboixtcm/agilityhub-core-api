package com.agilityhub.core.shared.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Lets a retrying use case commit its response and effects in the same transaction. */
public final class IdempotentOperation {
    private record Operation(Runnable lock,BiConsumer<Integer,byte[]> complete,AtomicBoolean released) { }
    private static final ThreadLocal<Operation> CURRENT=new ThreadLocal<>();
    private IdempotentOperation() { }
    public static Scope open(Runnable lock,BiConsumer<Integer,byte[]> complete) {
        var previous=CURRENT.get(); var operation=new Operation(lock,complete,new AtomicBoolean()); CURRENT.set(operation);
        return new Scope() {
            @Override public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
            @Override public boolean released() { return operation.released().get(); }
        };
    }
    public static void lock() { var operation=CURRENT.get(); if(operation!=null) operation.lock().run(); }
    public static void complete(int status,byte[] response) { var operation=CURRENT.get(); if(operation!=null) operation.complete().accept(status,response); }
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
