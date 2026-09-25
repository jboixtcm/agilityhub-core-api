package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.identity.application.CensusIdentityService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class MemberService {
    private static final Set<String> EDITABLE = Set.of("idDocument", "firstName", "lastName1", "lastName2", "gender", "birthDate", "contactEmails", "phones", "address", "remarks", "internalNotes", "consents", "version");
    private final CensusAccess access; private final CensusValidation validation; private final CensusEvents events;
    private final CountryContacts countries; private final CensusClubSettings settings; private final ClubConfigService configs;
    private final CensusIdentityService identities; private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<SignupService> signups;
    public MemberService(CensusAccess access, CensusValidation validation, CensusEvents events, CountryContacts countries,
            CensusClubSettings settings, ClubConfigService configs, CensusIdentityService identities, Clock clock) {
        this.access = access; this.validation = validation; this.events = events; this.countries = countries;
        this.settings = settings; this.configs = configs; this.identities = identities; this.clock = clock;
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_UPDATED, entityType = "'Member'", entity = "#id", member = "#id")
    public void patch(String id, Map<String,Object> request, boolean own) { edit(id, request, own); }
    @Transactional
    @Audited(action = AuditAction.SIGNUP_EDITED, entityType = "'Member'", entity = "#id", member = "#id")
    public void patchPending(String id, Map<String,Object> request) { edit(id, request, false); }
    private void edit(String id, Map<String,Object> request, boolean own) {
        var member = access.mutableMember(id);
        if (own && !access.me().id.equals(id)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        boolean pending="PENDING".equals(member.status);
        var editable=new HashSet<>(EDITABLE);
        if(pending) { editable.remove("consents");editable.addAll(Set.of("paymentMethod","signup")); }
        allow(request, own ? Set.of("contactEmails", "phones", "address", "version") : editable);
        version(member.version(), request.get("version"));
        // R-04-06 (E38): during a pending readmission, a D2 edit of the person fields edits the submitted values; the LEFT
        // record keeps its own until validation applies them.
        boolean readmission = pending && signups.getObject().readmissionPending(member);
        var target = readmission ? signups.getObject().submittedView(member) : member;
        var before = snapshot(member, target);
        var paymentBefore=target.paymentMethod;
        if (request.containsKey("idDocument")) {
            var document = validation.idDocument(request.get("idDocument"));
            // R-04-06 (b): the readmission matched on this document, so it cannot change while the readmission is pending; a
            // wrong one is resolved by rejecting the readmission. Sending the document it already has is no edit.
            if (readmission) {
                if (!document.equals(select(map(member.idDocument), "type", "number"))) { throw new ApiException(ErrorCode.INVALID_STATE, Map.of("reason", "READMISSION_PENDING")); }
            } else { member.idDocument = document; }
        }
        if (request.containsKey("firstName")) { target.firstName = text(request.get("firstName"), "firstName", 60, true); }
        if (request.containsKey("lastName1")) { target.lastName1 = text(request.get("lastName1"), "lastName1", 60, true); }
        if (request.containsKey("lastName2")) { target.lastName2 = text(request.get("lastName2"), "lastName2", 60, false); }
        if (request.containsKey("gender")) {
            if (!Set.of("MALE", "FEMALE", "OTHER").contains(String.valueOf(request.get("gender")))) { throw invalid("gender", "INVALID_VALUE"); }
            target.gender = string(request.get("gender"));
        }
        if (request.containsKey("birthDate")) {
            try { target.birthDate = LocalDate.parse(string(request.get("birthDate"))); }
            catch (RuntimeException badDate) { throw invalid("birthDate", "INVALID_VALUE"); }
            // S04 §3 (M21): in the past and not before 1900-01-01.
            if (!target.birthDate.isBefore(clock.instant().atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate())
                    || target.birthDate.isBefore(LocalDate.of(1900, 1, 1))) { throw invalid("birthDate", "INVALID_VALUE"); }
        }
        if (request.containsKey("contactEmails")) { target.contactEmails = validation.emails(request.get("contactEmails"), target.contactEmails); }
        if (request.containsKey("phones")) { target.phones = validation.phones(request.get("phones")); }
        if (request.containsKey("address")) { target.address = validation.address(request.get("address")); }
        if (pending && request.containsKey("paymentMethod")) {
            var payment=new LinkedHashMap<>(map(request.get("paymentMethod")));
            if(payment.containsKey("sepa")) { payment.putAll(map(payment.remove("sepa"))); }
            // R-04-19 (E3-T10): a SEPA_DD → SEPA_DD PATCH is partial. The IBAN, holder and holder tax id the request does not
            // send are kept, and so is the mandate signature date (R-04-10: `mandateSignedAt = submittedAt`).
            var current=map(target.paymentMethod);
            boolean sepa="SEPA_DD".equals(payment.get("type"))&&"SEPA_DD".equals(current.get("type"));
            if(sepa) { for(String key:List.of("iban","holderName","holderTaxId")) { if(!payment.containsKey(key)&&current.get(key)!=null) { payment.put(key,current.get(key)); } } }
            var next=signups.getObject().payment(payment,member,target.firstName+" "+target.lastName1);
            if(sepa&&next!=null&&current.get("mandateSignedAt")!=null) { next=new LinkedHashMap<>(next);next.put("mandateSignedAt",current.get("mandateSignedAt")); }
            target.paymentMethod=next;
        }
        if (readmission) { signups.getObject().storeSubmitted(member, target); }
        if (pending && request.containsKey("signup")) {
            var signup=map(request.get("signup"));allow(signup,Set.of("planIdRequested"));
            signups.getObject().requirePlan(string(signup.get("planIdRequested")));
            member.signup=new LinkedHashMap<>(map(member.signup));member.signup.putAll(signup);
        }
        if (request.containsKey("remarks")) { member.remarks = text(request.get("remarks"), "remarks", 2000, false); }
        if (request.containsKey("internalNotes")) { member.internalNotes = text(request.get("internalNotes"), "internalNotes", 2000, false); }
        if (request.containsKey("consents")) {
            var consent = map(request.get("consents")); allow(consent, Set.of("imageRights"));
            var image = map(consent.get("imageRights")); allow(image, Set.of("granted"));
            if (!(image.get("granted") instanceof Boolean)) { throw invalid("consents.imageRights.granted", "REQUIRED"); }
            member.consents = ConsentLedgers.image(member.consents,(Boolean)image.get("granted"),
                    configs.privacyPolicy(TenantContext.require()).version(),clock.instant(),CurrentUser.current().accountId());
        }
        // R-14-09 (E3-T09): the payload diff is masked like the audit (identity document, IBAN, holder tax id).
        var diff = events.maskedDiff(before, snapshot(member, target));
        if (diff.isEmpty() && Objects.equals(paymentBefore,target.paymentMethod)) { return; }
        access.members.save(member);
        events.emit("PENDING".equals(member.status) ? "SignupEdited" : "MemberUpdated", "Member", id, object("memberId", id, "diff", diff));
        // R-14-01 (M11): a pending row of D1 changed (name, method, plan); D1 is refreshed right after the commit.
        if (pending) { signups.getObject().refreshDashboard(); }
    }
    /**
     * The edited fields; the person ones come from {@code person} (the member, or the submitted values of a pending
     * readmission). The identity document and the bank data use the audit's annotated records, so {@link CensusEvents#maskedDiff}
     * masks them like the audit.
     */
    private Map<String,Object> snapshot(Member member, Member person) {
        return object("idDocument", new CensusAudit.IdentityDocument(string(map(member.idDocument).get("type")), string(map(member.idDocument).get("number"))),
                "firstName", person.firstName, "lastName1", person.lastName1, "lastName2", person.lastName2,
                "gender", person.gender, "birthDate", person.birthDate, "contactEmails", person.contactEmails, "phones", person.phones,
                "address", person.address, "paymentMethod", person.paymentMethod == null ? null : CensusAudit.bank(person.paymentMethod), "signup", member.signup,
                "remarks", member.remarks, "internalNotes", member.internalNotes, "consents", member.consents);
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_PAYMENT_METHOD_CHANGED, entityType = "'Member'", entity = "#id", member = "#id")
    public void payment(String id, Map<String,Object> request) {
        var member = access.mutableMember(id); access.require(Module.BILLING);
        allow(request, Set.of("type", "sepa", "card", "manual"));
        String type = string(request.get("type"));
        String provider = switch (String.valueOf(type)) { case "SEPA_DD" -> "SEPA_XML"; case "CARD" -> "STRIPE"; case "MANUAL" -> "MANUAL"; default -> throw invalid("type", "INVALID_VALUE"); };
        if (!settings.providerEnabled(provider)) { throw new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED); }
        if ("CARD".equals(type)) {
            // S12 supplies setup-intent confirmation; never accept an unverified Stripe reference.
            throw new ApiException(ErrorCode.NOT_IMPLEMENTED);
        }
        Map<String,Object> payment = object("type", type);
        if ("SEPA_DD".equals(type)) {
            var sepa = map(request.get("sepa")); allow(sepa, Set.of("iban", "holderName", "holderTaxId"));
            String iban = string(sepa.get("iban")); if (iban != null) { iban = iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT); }
            if (iban != null && !countries.iban(iban)) { throw new ApiException(ErrorCode.INVALID_IBAN); }
            String tax = text(sepa.get("holderTaxId"), "sepa.holderTaxId", 80, false);
            if (tax != null && "ES".equals(countries.countryCode())
                    && !countries.document(tax.startsWith("X") || tax.startsWith("Y") || tax.startsWith("Z") ? "NIE" : "DNI", tax)) { throw invalid("sepa.holderTaxId", "INVALID_VALUE"); }
            payment.putAll(object("iban", iban, "holderName", text(sepa.get("holderName"), "sepa.holderName", 120, true), "holderTaxId", tax));
            if ("SEPA_DD".equals(map(member.paymentMethod).get("type"))) {
                payment.putAll(select(map(member.paymentMethod), "mandateRef", "mandateSignedAt"));
            }
        } else {
            var manual = map(request.get("manual")); allow(manual, Set.of("channel"));
            String channel = text(manual.get("channel"), "manual.channel", 30, true);
            if (!Set.of("cash", "transfer", "bizum").contains(channel)) { throw invalid("manual.channel", "INVALID_VALUE"); }
            payment.put("channel", channel);
        }
        if (payment.equals(member.paymentMethod)) { return; }
        member.paymentMethod = payment; access.members.save(member);
        events.emit("MemberPaymentMethodChanged", "Member", id, object("memberId", id, "type", type,
                "masked", com.agilityhub.core.clubs.census.domain.CensusRules.maskedIban(string(payment.get("iban")))));
    }
    @Transactional
    @Audited(action = AuditAction.BOOKING_BLOCK_SET, entityType = "'Member'", entity = "#id", member = "#id", reason = "#reason")
    public void block(String id, String reason) {
        var member = access.mutableMember(id); reason = text(reason, "reason", 200, true);
        if (Boolean.TRUE.equals(map(member.bookingBlock).get("active"))) { throw new ApiException(ErrorCode.BOOKING_BLOCK_ALREADY_ACTIVE); }
        member.bookingBlock = object("active", true, "reason", reason, "since", clock.instant(), "byAccountId", CurrentUser.current().accountId());
        access.members.save(member); events.emit("BookingBlockChanged", "Member", id, object("memberId", id, "active", true, "reason", reason));
    }
    @Transactional
    @Audited(action = AuditAction.BOOKING_BLOCK_CLEARED, entityType = "'Member'", entity = "#id", member = "#id")
    public void unblock(String id) {
        var member = access.mutableMember(id);
        if (!Boolean.TRUE.equals(map(member.bookingBlock).get("active"))) { throw new ApiException(ErrorCode.BOOKING_BLOCK_NOT_ACTIVE); }
        member.bookingBlock = object("active", false); access.members.save(member);
        events.emit("BookingBlockChanged", "Member", id, object("memberId", id, "active", false));
    }
    @Transactional
    @Audited(action = AuditAction.ACCESS_RESENT, entityType = "'Member'", entity = "#id", member = "#id", reason = "'ACCESS_RESENT'")
    public String resend(String id, String ip) {
        var member = access.mutableMember(id);
        if (!"ACTIVE".equals(member.status) || member.accountId == null) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        String email = identities.accessEmail(member.accountId); identities.limitResend(email, ip);
        events.emit("AccessResent", "Member", id, object("memberId", id, "accountId", member.accountId)); return email;
    }
}
