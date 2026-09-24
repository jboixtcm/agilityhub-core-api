package com.agilityhub.core.shared.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;

/**
 * E3-T09 step 2 (M17, R-04-20, R-04-17): a real Tomcat (the `server.forward-headers-strategy: native` RemoteIpValve only
 * runs there, never under MockMvc) receives raw HTTP/1.1 requests from 127.0.0.1, with an `X-Forwarded-For` header like
 * the one Caddy injects. Raw sockets, because the JDK clients refuse to set `Host`, which resolves the tenant.
 */
abstract class ForwardedClientSupport extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    String club,host,plan;
    int sequence;
    record Reply(int status,String body) { }
    @BeforeEach void fixtureClub() {
        club="px-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of())));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
    }
    Reply post(String path,String forwardedFor,Object body,String idempotencyKey) throws IOException {
        byte[] content=mapper.writeValueAsBytes(body);
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(60_000);
            var head=new StringBuilder("POST "+path+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+content.length+"\r\n");
            if(forwardedFor!=null) head.append("X-Forwarded-For: ").append(forwardedFor).append("\r\n");
            if(idempotencyKey!=null) head.append("Idempotency-Key: ").append(idempotencyKey).append("\r\n");
            head.append("\r\n");
            var out=socket.getOutputStream();out.write(head.toString().getBytes(StandardCharsets.US_ASCII));out.write(content);out.flush();
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(response.substring(9,12)),response.substring(response.indexOf("\r\n\r\n")+4));
        }
    }
    Map<String,Object> identityCheck() {
        int value=16000000+(++sequence);
        return Map.of("idDocument",Map.of("type","DNI","value",String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23)),"emails",List.of("proxy"+sequence+"@example.test"));
    }
    Map<String,Object> signup() {
        int value=16100000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);
        return Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Forwarded","birthDate","2000-01-01","gender","FEMALE","emails",List.of("forwarded"+sequence+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog","sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000006"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1")));
    }
    /** The `ipHash` of the consent entries of the member a signup created. */
    List<String> consentHashes(String memberId) {
        return mongo.getCollection("members").find(new Document("_id",memberId)).first().getList("consents",Document.class).stream().map(entry->entry.getString("ipHash")).toList();
    }
}
