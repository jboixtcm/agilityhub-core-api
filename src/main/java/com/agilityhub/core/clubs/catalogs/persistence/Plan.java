package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.CatalogEntity;
import com.agilityhub.core.clubs.catalogs.domain.OfferEntity;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;

@Document("plans")
public record Plan(@Id String id, String clubId, String code, LocalizedText name, PlanType type,
        BillingMode billingMode, int dogsIncluded, EntryFee entryFee, Pack pack, SingleClass singleClass,
        LocalizedText conditions, Texts texts, boolean showOnSignup, boolean showOnWeb, int order, boolean active,
        long version, Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId)
        implements CatalogEntity, OfferEntity { }
