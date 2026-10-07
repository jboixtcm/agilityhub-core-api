package com.agilityhub.core.shared.application;

/** Billing's handoff to S14 without a payment/common context cycle. */
public interface AccountingExportPort {
    record Result(String jobId, String fileName, String contentType, byte[] content) { }
    Result export(String period, String format);
}
