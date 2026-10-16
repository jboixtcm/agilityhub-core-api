package com.agilityhub.core.platform.application.audit;

/** Implemented subset of the closed action list in S14 R-14-09. */
public enum AuditAction {
    ACTIVITY_PUBLISHED, ACTIVITY_CANCELLED, ACTIVITY_UPDATED, ACTIVITY_REGISTERED_BY_CLUB, ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB,
    WEEK_VALIDATED, CLASS_CANCELLED, CLASS_UPDATED_WITH_BOOKINGS, CLASS_RISK_EXEMPTION_CHANGED, RING_BLOCK_CREATED, RING_BLOCK_CANCELLED,
    TEMPLATE_CLASS_DELETED, TEMPLATE_BAND_DELETED,
    MEMBER_VALIDATED, SIGNUP_REJECTED, SIGNUP_SUBMITTED,
    MEMBER_UPDATED, SIGNUP_EDITED, MEMBER_PAYMENT_METHOD_CHANGED, MEMBER_STATUS_CHANGED,
    BOOKING_CREATED_BY_CLUB, BOOKING_CANCELLED_BY_CLUB, BOOKING_CANCELLED_LATE,
    TRAINING_BOOKED_BY_CLUB, TRAINING_CANCELLED_BY_CLUB,
    ATTENDANCE_OVERRIDDEN,
    BOOKING_BLOCK_SET, BOOKING_BLOCK_CLEARED, ACCESS_RESENT, DOG_UPDATED, DOG_LEVEL_CHANGED,
    DOG_FREE_TRAINING_CHANGED, DOG_TRANSFERRED, DOG_DEACTIVATED, DOG_REACTIVATED,
    DOG_DOCUMENT_FILE_REMOVED, FAMILY_GROUP_CHANGED,
    MEMBER_ROLES_CHANGED,
    DATA_EXPORTED,
    CATALOG_CHANGED,
    /**
     * S11 R-11-13 «Enviar comunicat» (E7-T01 contract, written by E7-T04): an «event» action on the template, no `changes`,
     * `details {batchId, recipientCount, filters, selection}` (E7-T07).
     */
    ANNOUNCEMENT_SENT,
    MIGRATION_APPLIED,
    PLATFORM_ROLES_CHANGED,
    ONBOARDING_COMPLETED,
    IMPERSONATION_STARTED,
    ACCOUNT_EMAIL_STATUS_CHANGED,
    PARAMETER_CHANGED,
    CLUB_UPDATED,
    CLUB_MODULES_CHANGED,
    CLUB_STATUS_CHANGED,
    /** S17 console (E108, written by E10-T02): a club created from a definition, a payment provider changed, an administrator invited. */
    CLUB_CREATED, CLUB_PAYMENT_PROVIDER_CHANGED, CLUB_ADMIN_INVITED,
    /**
     * S17 console (E10-T04, S17 §13 proposals entered in R-14-09): a club domain added, verified, made primary or removed
     * (R-17-02/R-17-03), a support access started (R-17-10, in the target club's audit) and the public key rotated (R-17-11).
     * Round 2 (E123): a live support access ended by the club's ADMIN (R-17-10 «revocable»).
     */
    CLUB_DOMAIN_CHANGED, SUPPORT_ACCESS_STARTED, SUPPORT_ACCESS_ENDED, PUBLIC_API_KEY_ROTATED,
    JOB_TRIGGERED,
    /**
     * S12 (E8-T02): the invoice state actions (R-12-16, R-12-17, R-12-19), the manual adjustment invoice (R-12-19, S12 §13)
     * and the billing run's generation and rollback (R-12-11, R-12-14; one entry per run, `details.invoiceIds[]`).
     */
    INVOICE_MARKED_PAID, INVOICE_MARKED_FAILED, INVOICE_CANCELLED, INVOICE_CREATED_MANUAL, REMITTANCE_GENERATED, REMITTANCE_ROLLED_BACK,
    /** S12 R-12-15 (E8-T03): the remittance marked as sent to the bank (`GENERATED → SUBMITTED`, `submittedAt`). */
    REMITTANCE_SUBMITTED, PAYMENT_REFUNDED, PAYMENT_REFUND_INTERVENTION, CARD_CHARGES_STARTED, UPFRONT_PAYMENT_RECORDED,
    /** S12 R-12-24 and S13 lifecycle actions (E8-T05), already in S14 R-14-09's closed list. */
    PACK_ADJUSTED, MEMBER_PLAN_CHANGED, INACTIVITY_RESOLVED, LEAVE_RESOLVED, LEAVE_CANCELLED,
    /** S16 R-16-04 (E9-T02): a ring geometry saved or removed (`entityType = RingGeometry`, `entityId` = the ring). */
    RING_GEOMETRY_CHANGED,
    /**
     * S16 (E9-T03): a placement stored with a `critical` warning through `force` (R-16-05, `entityType = Placement`, `details.warningIds`)
     * and a ring setup registered (R-16-07, `entityType = RingSetup`, the replaced one in `details.replacedSetupId`).
     */
    COURSE_FORCED_PLACEMENT, RING_SETUP_REGISTERED,
    /**
     * S14 RGPD (E11-T01): the member's data package ready (R-14-13, `entityType = ExportJob`), an erasure requested or cancelled
     * (R-14-14, `entityType = ErasureRequest`), and the erasure's per-club `MEMBER_ERASED` and the account's `ACCOUNT_ERASED`
     * (R-14-15: entries that only carry ids). All «event» actions: no `changes`.
     */
    MEMBER_DATA_EXPORTED, ERASURE_REQUESTED, ERASURE_CANCELLED, MEMBER_ERASED, ACCOUNT_ERASED
}
