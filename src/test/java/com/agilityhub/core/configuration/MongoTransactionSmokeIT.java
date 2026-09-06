package com.agilityhub.core.configuration;

import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoTransactionSmokeIT extends AbstractIntegrationTest {

    private static final String COLLECTION = "transaction_smoke";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoTransactionManager transactionManager;

    private List<SmokeDocument> documents;

    @BeforeEach
    void prepareCollection() {
        mongoTemplate.dropCollection(COLLECTION);
        mongoTemplate.createCollection(COLLECTION);
        documents = fixtures.readList("fixtures/transaction-smoke.json", SmokeDocument.class);
    }

    @AfterEach
    void cleanCollection() {
        mongoTemplate.dropCollection(COLLECTION);
    }

    @Test
    void E0_T02_transactionCommitsBothDocuments() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mongoTemplate.insert(documents.get(0), COLLECTION);
            mongoTemplate.insert(documents.get(1), COLLECTION);
        });

        assertThat(mongoTemplate.findAll(SmokeDocument.class, COLLECTION))
                .containsExactlyInAnyOrderElementsOf(documents);
    }

    @Test
    void E0_T02_failingTransactionRollsBackBothDocuments() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mongoTemplate.insert(documents.get(0), COLLECTION);
            mongoTemplate.insert(documents.get(1), COLLECTION);
            assertThat(mongoTemplate.findAll(SmokeDocument.class, COLLECTION)).hasSize(2);
            throw new IllegalStateException("Simulated failure after both writes");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure after both writes");

        assertThat(mongoTemplate.findAll(SmokeDocument.class, COLLECTION)).isEmpty();
    }

    record SmokeDocument(@Id String id, String value) {
    }
}
