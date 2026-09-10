package com.agilityhub.core.clubs.dashboard.application.ports;


public interface RiskEvaluator {
    /** S06 owns the risk policy; this task supplies only a false null object. */
    boolean atRisk(ClassSessionsQuery.Session session);
}
