package com.agilityhub.core.shared.application;

/**
 * The open club's team serialisation boundary (`team_write_locks`; S05 team changes, S03 roles and S17 R-17-09): every writer that can remove an
 * active administrator takes it first in its transaction, so the last-admin count is read under it and two concurrent removals
 * conflict instead of both committing.
 */
public interface TeamWriteLock {
    void lock();
}
