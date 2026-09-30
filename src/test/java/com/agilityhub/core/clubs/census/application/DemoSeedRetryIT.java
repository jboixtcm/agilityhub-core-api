package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.training.application.DemoTrainingSeeder;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.definition.ClubDefinitionCodec;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.TransientClientSessionException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E5-T29 step 11 (web E5-W04 round-2 question R2-1): a `TransientTransactionError` during `seed:demo` (the driver's error behind
 * Spring's `TransientClientSessionException`) retries that transaction, as the retried writers do (`TransactionRetries`),
 * instead of failing the command. The failure is injected once, inside the demo-planning transaction, after the earlier steps
 * have written: the retry runs the whole transaction again, and a second run reports 0 changes.
 */
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoSeedRetryIT extends AbstractIntegrationTest {
    @MockitoSpyBean DemoTrainingSeeder training;
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command;
    @Autowired HostTenantResolver hosts; @Autowired MongoTemplate mongo;

    String run(String... args) {
        var out = new ByteArrayOutputStream(); var original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { command.run(new DefaultApplicationArguments(args)); }
        finally { System.setOut(original); }
        String printed = out.toString(StandardCharsets.UTF_8); original.print(printed); return printed;
    }

    @Test void T_06_28_aTransientTransactionErrorRetriesTheDemoSeedTransactionAndASecondRunChangesNothing() {
        wipeDatabaseKeepingBootstrap(); hosts.invalidate(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        String club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
        var labelled = new com.mongodb.MongoException(251, "Fictional NoSuchTransaction"); labelled.addLabel("TransientTransactionError");
        doThrow(new TransientClientSessionException("Fictional transient transaction error", labelled)).doCallRealMethod().when(training).apply(any());
        String first = run("--club=canic", "--seed=42", "--week-start=2026-09-14");
        verify(training, times(2)).apply(any());
        assertThat(first).contains(" changes (demo planning, week start 2026-09-14)").doesNotContain("\n0 changes (demo planning");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club).and("_id").regex("^" + club + ":planning")), "demo_seed_runs")).isEqualTo(1);
        var weeks = mongo.findAll(Document.class, "weeks").stream().map(w -> w.getString("startDate")).toList();
        assertThat(weeks).as("each week once: the failed attempt left nothing").doesNotHaveDuplicates().isNotEmpty();
        long bookings = mongo.count(new Query(), "bookings"), trainings = mongo.count(new Query(), "training_bookings");
        String second = run("--club=canic", "--seed=42", "--week-start=2026-09-14");
        assertThat(second).contains("\n0 changes (demo seed)").contains("\n0 changes (demo planning, week start 2026-09-14)");
        assertThat(mongo.count(new Query(), "bookings")).isEqualTo(bookings); assertThat(mongo.count(new Query(), "training_bookings")).isEqualTo(trainings);
        verify(training, times(2)).apply(any());
    }
}
