package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
    private final ObjectMapper mapper;
    public PlanValidator(ObjectMapper mapper) { this.mapper = mapper; }
    public <T> T convert(Object raw, Class<T> type) {
        try { return mapper.convertValue(raw, type); }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    public LocalizedText text(Object raw, String field, int max, boolean required, ClubConfig config) {
        if (!required && (raw == null || raw instanceof Map<?, ?> map && map.isEmpty())) { return null; }
        if (!(raw instanceof Map<?, ?> map) || !map.containsKey(config.club().defaultLocale())) { throw invalid(field, "VALIDATION_ERROR"); }
        Map<String, String> texts = new LinkedHashMap<>();
        map.forEach((locale, value) -> {
            if (!config.club().locales().contains(locale)) { throw invalid(field, "LOCALE_NOT_ENABLED"); }
            if (!(value instanceof String text) || text.isBlank() || text.length() > max) { throw invalid(field, "VALIDATION_ERROR"); }
            texts.put(locale.toString(), value.toString().strip());
        });
        return new LocalizedText(texts, config.club().defaultLocale());
    }
    public Texts texts(Object raw, ClubConfig config) {
        Map<?, ?> values = raw instanceof Map<?, ?> map ? map : Map.of();
        return new Texts(text(values.get("description"), "texts.description", 1000, false, config),
                text(values.get("offerLabel"), "texts.offerLabel", 80, false, config), text(values.get("priceLabel"), "texts.priceLabel", 80, false, config));
    }
    public EntryFee entryFee(Object raw, ClubConfig config) {
        var fee = raw == null ? new EntryFee(EntryFeeMode.STANDARD, null, null) : convert(raw, EntryFee.class);
        if (fee.mode() == null) { throw invalid("entryFee.mode", "VALIDATION_ERROR"); }
        switch (fee.mode()) {
            case AMOUNT -> {
                if (fee.amount() == null || fee.amount().amountMinor() < 0 || fee.percent() != null) { throw invalid("entryFee", "VALIDATION_ERROR"); }
                if (!fee.amount().currency().equals(config.club().currency())) { throw new ApiException(ErrorCode.CURRENCY_MISMATCH); }
            }
            case PERCENT -> {
                if (fee.percent() == null || fee.percent() < 1 || fee.percent() > 100 || fee.amount() != null) { throw invalid("entryFee", "VALIDATION_ERROR"); }
            }
            default -> { if (fee.amount() != null || fee.percent() != null) { throw invalid("entryFee", "VALIDATION_ERROR"); } }
        }
        return fee;
    }
    public Pack pack(Object raw) {
        var value = convert(raw, Pack.class);
        if (value == null || value.sessions() < 1 || value.sessions() > 99 || value.validityMonths() < 1 || value.validityMonths() > 24) {
            throw invalid("pack", "VALIDATION_ERROR");
        }
        return value;
    }
    public SingleClass single(Object raw, ClubConfig config) {
        var value = convert(raw, SingleClass.class);
        if (value == null || value.chargeMode() == null) { throw invalid("singleClass", "VALIDATION_ERROR"); }
        return new SingleClass(value.chargeMode(), value.cancelPolicy() == null
                ? CancelPolicy.valueOf(config.get("billing.singleClassCancelPolicy", String.class)) : value.cancelPolicy());
    }
    public ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", code))));
    }
}
