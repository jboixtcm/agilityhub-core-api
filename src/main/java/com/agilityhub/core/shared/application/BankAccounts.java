package com.agilityhub.core.shared.application;

import java.util.List;
import java.util.Map;

/**
 * Ruling E43, A37 (INC-31, E11-T02): how the census stores a member's IBAN without ever holding it in clear at rest. The payments
 * vault implements it; only the SEPA remittance writer reads an IBAN back (R-12-12). A stored account is one of two shapes of
 * the {@link #FIELDS}: `{ibanEncrypted, ibanLast4}` (sealed for the member: AES-GCM with `BILLING_BANK_KEY`, the club and the
 * member as associated data) or, only where `BILLING_BANK_KEY` is not set (a local stack; production Compose requires it),
 * `{iban}`. `census:seal-ibans` turns the second shape into the first.
 */
public interface BankAccounts {
    /** The fields that hold a member's account, in either shape. */
    List<String> FIELDS = List.of("iban", "ibanEncrypted", "ibanLast4");

    /** Whether `BILLING_BANK_KEY` is set, so {@link #seal} encrypts (always in production; `census:seal-ibans` requires it). */
    boolean configured();

    /** The stored fields of {@code iban} (normalised, valid) for this member: sealed when the key is set. */
    Map<String, Object> seal(String iban, String clubId, String memberId);

    /**
     * Whether two stored accounts of one member (either shape) hold the same IBAN. The comparison happens inside the vault and
     * answers only yes or no: the census decides on it whether a mandate or a readmission changed (R-03-07, R-04-06).
     */
    boolean same(Map<String, Object> first, Map<String, Object> second, String clubId, String memberId);
}
