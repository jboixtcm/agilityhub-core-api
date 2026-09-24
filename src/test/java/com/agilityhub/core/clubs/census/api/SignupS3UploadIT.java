package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.clubs.followup.persistence.S3AttachmentStorage;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E3-T09 step 1 (M16, R-04-08): the signup upload against an S3-compatible store. The presigned PUT is signed with
 * `Content-Type` and `If-None-Match: *` (`S3AttachmentStorage`); the signup route returns both in `headers`, and only a
 * PUT that sends them is accepted, once.
 *
 * <p>The store is LocalStack's S3 with signature validation on, and its default test credentials. The task asked for
 * MinIO, but its public images can no longer be pulled (`minio/minio`: «repository does not exist»; `quay.io/minio/minio`:
 * 401), so neither this machine nor the CI runner can start it (E3-T09 report).
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(SignupS3UploadIT.S3Storage.class)
class SignupS3UploadIT extends AbstractIntegrationTest {
    static final String USER="test", SECRET="test", BUCKET="signup-uploads";
    @SuppressWarnings("resource")
    static final GenericContainer<?> S3=new GenericContainer<>("localstack/localstack:3.8.1")
            .withEnv("SERVICES","s3").withEnv("S3_SKIP_SIGNATURE_VALIDATION","0").withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)).withStartupTimeout(Duration.ofMinutes(5));
    static { S3.start(); }
    static URI endpoint() { return URI.create("http://"+S3.getHost()+":"+S3.getMappedPort(4566)); }
    static StaticCredentialsProvider credentials() { return StaticCredentialsProvider.create(AwsBasicCredentials.create(USER,SECRET)); }
    static S3Client client() {
        return S3Client.builder().region(Region.US_EAST_1).credentialsProvider(credentials()).endpointOverride(endpoint()).forcePathStyle(true).build();
    }

    /** The production storage class against the store; path-style addressing because the store runs on a bare host:port. */
    @TestConfiguration(proxyBeanMethods=false)
    static class S3Storage {
        @Bean @Primary AttachmentStorage s3CompatibleAttachments(Clock clock) {
            var s3=client();
            if(s3.listBuckets().buckets().stream().noneMatch(b->b.name().equals(BUCKET))) s3.createBucket(b->b.bucket(BUCKET));
            var signer=S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(credentials()).endpointOverride(endpoint())
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
            return new S3AttachmentStorage(s3,signer,BUCKET,clock);
        }
    }

    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    String club,host,plan;
    final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    @BeforeEach void fixtureClub() {
        club="s3-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of())));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    HttpResponse<String> put(String url,Map<String,String> headers,byte[] body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void R_04_08_T_04_13_signedSignupUploadNeedsTheReturnedHeadersAndWritesOnce() throws Exception {
        byte[] bytes="fictional vaccination card".getBytes(StandardCharsets.UTF_8);
        var upload=result(postJson("/signup/upload-urls",Map.of("fileName","vaccination.pdf","contentType","application/pdf","sizeBytes",bytes.length)),200);
        Map<String,String> headers=mapper.convertValue(upload.path("headers"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() { });
        assertThat(headers).containsEntry("Content-Type","application/pdf").containsEntry("If-None-Match","*");
        String url=upload.path("uploadUrl").asText();assertThat(url).startsWith(endpoint().toString());
        // Without the signed If-None-Match the signature does not match.
        var unsigned=put(url,Map.of("Content-Type","application/pdf"),bytes);
        assertThat(unsigned.statusCode()).as(unsigned.body()).isEqualTo(403);
        var accepted=put(url,headers,bytes);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        // The condition: the same signed URL cannot overwrite the object (same length: Content-Length is signed too).
        var again=put(url,headers,"another vaccination card!!".getBytes(StandardCharsets.UTF_8));
        assertThat("another vaccination card!!".length()).isEqualTo(bytes.length);
        assertThat(again.statusCode()).as("If-None-Match: * refuses an existing key: %s",again.body()).isEqualTo(412);
        try(var s3=client()) { assertThat(s3.getObjectAsBytes(b->b.bucket(BUCKET).key(upload.path("fileKey").asText())).asByteArray()).isEqualTo(bytes); }
        // The submission claims the stored object (HEAD on the store).
        var body=signup();((ObjectNode)body.get("dog")).set("documents",mapper.valueToTree(List.of(Map.of("type","VACCINATION_CARD","files",List.of(Map.of("fileKey",upload.path("fileKey").asText(),"name","vaccination.pdf"))))));
        result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),201);
        assertThat(mongo.getCollection("dog_documents").find(new Document("clubId",club)).first().getString("state")).isEqualTo("RECEIVED");
    }
    ObjectNode signup() {
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value","15000001"+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(15000001%23)),"firstName","Example","lastName1","Uploader","birthDate","2000-01-01","gender","FEMALE","emails",List.of("uploader@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog","sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000005000001"),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
}
