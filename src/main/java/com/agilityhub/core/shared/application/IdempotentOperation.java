package com.agilityhub.core.shared.application;

import java.util.function.BiConsumer;

/** Lets a retrying use case commit its response and effects in the same transaction. */
public final class IdempotentOperation {
    private record Operation(Runnable lock,BiConsumer<Integer,byte[]> complete) { }
    private static final ThreadLocal<Operation> CURRENT=new ThreadLocal<>();
    private IdempotentOperation() { }
    public static Scope open(Runnable lock,BiConsumer<Integer,byte[]> complete) {
        var previous=CURRENT.get(); CURRENT.set(new Operation(lock,complete));
        return () -> { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void lock() { var operation=CURRENT.get(); if(operation!=null) operation.lock().run(); }
    public static void complete(int status,byte[] response) { var operation=CURRENT.get(); if(operation!=null) operation.complete().accept(status,response); }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
