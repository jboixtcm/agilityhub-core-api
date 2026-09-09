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
        allow(request, own ? Set.of("contactEmails", "phones", "address", "version") : EDITABLE);
        version(member.version(), request.get("version"));
        var before = snapshot(member);
        if (request.containsKey("idDocument")) { member.idDocument = validation.idDocument(request.get("idDocument")); }
        if (request.containsKey("firstName")) { member.firstName = text(request.get("firstName"), "firstName", 60, true); }
        if (request.containsKey("lastName1")) { member.lastName1 = text(request.get("lastName1"), "lastName1", 60, true); }
        if (request.containsKey("lastName2")) { member.lastName2 = text(request.get("lastName2"), "lastName2", 60, false); }
        if (request.containsKey("gender")) {
            if (!Set.of("MALE", "FEMALE", "OTHER").contains(String.valueOf(request.get("gender")))) { throw invalid("gender", "INVALID_VALUE"); }
            member.gender = string(request.get("gender"));
        }
        if (request.containsKey("birthDate")) {
            try { member.birthDate = LocalDate.parse(string(request.get("birthDate"))); }
            catch (RuntimeException badDate) { throw invalid("birthDate", "INVALID_VALUE"); }
            if (member.birthDate.isAfter(clock.instant().atZone(ZoneId.of(access.config().club().timeZone())).toLocalDate())) { throw invalid("birthDate", "INVALID_VALUE"); }
        }
        if (request.containsKey("contactEmails")) { member.contactEmails = validation.emails(request.get("contactEmails"), member.contactEmails); }
        if (request.containsKey("phones")) { member.phones = validation.phones(request.get("phones")); }
        if (request.containsKey("address")) { member.address = validation.address(request.get("address")); }
        if (request.containsKey("remarks")) { member.remarks = text(request.get("remarks"), "remarks", 2000, false); }
        if (request.containsKey("internalNotes")) { member.internalNotes = text(request.get("internalNotes"), "internalNotes", 2000, false); }
        if (request.containsKey("consents")) {
            var consent = map(request.get("consents")); allow(consent, Set.of("imageRights"));
            var image = map(consent.get("imageRights")); allow(image, Set.of("granted"));
            if (!(image.get("granted") instanceof Boolean)) { throw invalid("consents.imageRights.granted", "REQUIRED"); }
            member.consents = new LinkedHashMap<>(map(member.consents));
            member.consents.put("imageRights", object("granted", image.get("granted"), "at", clock.instant(),
                    "version", configs.privacyPolicy(TenantContext.require()).version(), "byAccountId", CurrentUser.current().accountId()));
        }
        var diff = events.diff(before, snapshot(member));
        if (diff.isEmpty()) { return; }
        access.members.save(member);
        events.emit("PENDING".equals(member.status) ? "SignupEdited" : "MemberUpdated", "Member", id, object("memberId", id, "diff", diff));
    }
    private Map<String,Object> snapshot(Member member) {
        return object("idDocument", member.idDocument, "firstName", member.firstName, "lastName1", member.lastName1, "lastName2", member.lastName2,
                "gender", member.gender, "birthDate", member.birthDate, "contactEmails", member.contactEmails, "phones", member.phones,
                "address", member.address, "remarks", member.remarks, "internalNotes", member.internalNotes, "consents", member.consents);
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
