package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E11-T07 step 5: Spring Boot 4 no longer picks a UUID or BigDecimal/BigInteger representation. The api pins the ones Boot 3.5
 * used (`JAVA_LEGACY` UUIDs; Spring Data MongoDB 4.5's `STRING` numbers), so documents written before the upgrade read back
 * with the same value and scale and are written back byte for byte. Money stays `{amountMinor: int64, currency}`.
 */
class StoredRepresentationIT extends AbstractIntegrationTest {
    private static final String COLLECTION = "e11_t07_representation";
    private static final UUID TOKEN = UUID.fromString("0b9f1c2e-3d4a-4b5c-8d6e-7f8091a2b3c4");
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.678Z");

    @Autowired private MongoTemplate mongo;

    @Document(COLLECTION)
    record Stored(@Id String id, UUID token, BigDecimal ratio, BigInteger count, Money fee, List<Line> lines) { }
    record Line(BigDecimal taxPercent, Money total) { }

    @AfterEach void dropFixtures() {
        mongo.dropCollection(COLLECTION);
        mongo.getCollection("prices").deleteOne(eq("_id", "e11-t07-price"));
        mongo.getCollection("invoices").deleteOne(eq("_id", "e11-t07-invoice"));
    }

    @Test void E11_T07_uuidAndBigNumbersWrittenByBoot35ReadAndWriteBackUnchanged() {
        BsonDocument old = new BsonDocument("_id", new BsonString("e11-t07-legacy"))
                .append("token", new BsonBinary(BsonBinarySubType.UUID_LEGACY, new BsonBinary(TOKEN, UuidRepresentation.JAVA_LEGACY).getData()))
                .append("ratio", new BsonString("21.00"))
                .append("count", new BsonString("12345678901234567890"))
                .append("fee", money(4500))
                .append("lines", new BsonArray(List.of(new BsonDocument("taxPercent", new BsonString("10.50")).append("total", money(1234)))))
                .append("_class", new BsonString(Stored.class.getName()));
        raw(COLLECTION).insertOne(old.clone());

        Stored read = mongo.findById("e11-t07-legacy", Stored.class);
        assertThat(read).isEqualTo(new Stored("e11-t07-legacy", TOKEN, new BigDecimal("21.00"), new BigInteger("12345678901234567890"),
                new Money(4500, "EUR"), List.of(new Line(new BigDecimal("10.50"), new Money(1234, "EUR")))));
        assertThat(read.ratio().scale()).isEqualTo(2);

        mongo.save(read);
        assertThat(raw(COLLECTION).find(eq("_id", "e11-t07-legacy")).first()).isEqualTo(old);
        assertThat(old.getBinary("token").getType()).isEqualTo(BsonBinarySubType.UUID_LEGACY.getValue());
    }

    @Test void E11_T07_aPriceStoredBeforeTheUpgradeKeepsItsStringTaxPercentAndMoney() {
        // A price as Boot 3.5's insert stored it: @Field(targetType = STRING) never applied to LocalDate on a full-document write
        // (no LocalDate -> String converter, in Spring 6.2 as in 7.0), so validFrom is the JVM zone's midnight, as before.
        long validFrom = LocalDate.parse("2026-01-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        BsonDocument old = new BsonDocument("_id", new BsonString("e11-t07-price"))
                .append("clubId", new BsonString("club-e11-t07")).append("planId", new BsonString("plan-e11-t07"))
                .append("concept", new BsonString("MONTHLY_FEE")).append("amount", money(4500))
                .append("taxPercent", new BsonString("21.00")).append("validFrom", new BsonDateTime(validFrom))
                .append("version", new BsonInt64(3)).append("createdAt", new BsonDateTime(CREATED.toEpochMilli()))
                .append("updatedAt", new BsonDateTime(CREATED.toEpochMilli()))
                .append("createdByAccountId", new BsonString("account-e11-t07")).append("updatedByAccountId", new BsonString("account-e11-t07"))
                .append("_class", new BsonString(Price.class.getName()));
        raw("prices").insertOne(old.clone());

        Price read = mongo.findById("e11-t07-price", Price.class);
        assertThat(read.taxPercent()).isEqualTo(new BigDecimal("21.00"));
        assertThat(read.amount()).isEqualTo(new Money(4500, "EUR"));
        assertThat(read.validFrom()).isEqualTo(LocalDate.parse("2026-01-01"));

        // INC-68 (E11-T08): the write back stores the day as the `YYYY-MM-DD` string every write path uses; the rest is unchanged.
        mongo.save(read);
        assertThat(raw("prices").find(eq("_id", "e11-t07-price")).first()).isEqualTo(old.clone().append("validFrom", new BsonString("2026-01-01")));
    }

    @Test void E11_T07_anInvoiceLineStoredBeforeTheUpgradeKeepsItsStringTaxPercent() {
        BsonDocument line = new BsonDocument("lineNo", new BsonInt32(1)).append("origin", new BsonString("MONTHLY_FEE"))
                .append("priceId", new BsonString("e11-t07-price")).append("description", new BsonString("Quota mensual (fictícia)"))
                .append("base", money(3719)).append("taxPercent", new BsonString("21.00")).append("tax", money(781)).append("total", money(4500));
        BsonDocument old = new BsonDocument("_id", new BsonString("e11-t07-invoice"))
                .append("clubId", new BsonString("club-e11-t07")).append("series", new BsonString("R26")).append("number", new BsonInt64(7))
                .append("displayNumber", new BsonString("R26-0007")).append("issueDate", new BsonString("2026-01-02"))
                .append("period", new BsonString("2026-01")).append("memberId", new BsonString("member-e11-t07"))
                .append("memberSnapshot", new BsonDocument("number", new BsonInt32(12)).append("fullName", new BsonString("Persona Fictícia"))
                        .append("taxId", new BsonString("00000000T")))
                .append("lines", new BsonArray(List.of(line))).append("base", money(3719)).append("tax", money(781)).append("total", money(4500))
                .append("status", new BsonString("PENDING")).append("kind", new BsonString("MANUAL"))
                .append("includeInNextRun", BsonBoolean.TRUE).append("version", new BsonInt64(3))
                .append("createdAt", new BsonDateTime(CREATED.toEpochMilli())).append("createdByAccountId", new BsonString("account-e11-t07"))
                .append("updatedAt", new BsonDateTime(CREATED.toEpochMilli())).append("updatedByAccountId", new BsonString("account-e11-t07"))
                .append("_class", new BsonString(Invoice.class.getName()));
        raw("invoices").insertOne(old.clone());

        Invoice read = mongo.findById("e11-t07-invoice", Invoice.class);
        assertThat(read.lines()).singleElement().satisfies(item -> {
            assertThat(item.taxPercent()).isEqualTo(new BigDecimal("21.00"));
            assertThat(item.total()).isEqualTo(new Money(4500, "EUR"));
        });

        mongo.save(read);
        // @Version advances on save; everything else, the lines' taxPercent included, is written back as it was stored.
        BsonDocument written = raw("invoices").find(eq("_id", "e11-t07-invoice")).first();
        assertThat(written.getInt64("version")).isEqualTo(new BsonInt64(4));
        assertThat(written.clone().append("version", new BsonInt64(3))).isEqualTo(old);
    }

    private com.mongodb.client.MongoCollection<BsonDocument> raw(String collection) {
        return mongo.getCollection(collection).withDocumentClass(BsonDocument.class);
    }

    private static BsonDocument money(long amountMinor) {
        return new BsonDocument("amountMinor", new BsonInt64(amountMinor)).append("currency", new BsonString("EUR"));
    }
}
