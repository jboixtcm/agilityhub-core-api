package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformProviders {
    @org.springframework.beans.factory.annotation.Value("${core.hosted-demo.enabled:false}") private boolean hostedDemo;
    public record Summary(boolean enabled,boolean configured,String masked,String last4,String mode,Instant lastTestAt) { }
    public record Change(String id,@AuditField Map<String,Summary> providers) { }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired private ClubRepository clubs;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
    /** PAY-10: the request field that confirms a switch from saved live keys to test mode on the `prod` profile. */
    public static final String TEST_MODE_OVERRIDE="testModeOverride";
    private final PlatformClubChanges changes;private final ConsolePaymentAccess vault;private final PlatformConsoleProjection projection;
    private final ConsolePaymentAccess provider;private final Clock clock;
    public PlatformProviders(PlatformClubChanges changes,ConsolePaymentAccess vault,PlatformConsoleProjection projection,ConsolePaymentAccess provider,Clock clock) {
        this.changes=changes;this.vault=vault;this.projection=projection;this.provider=provider;this.clock=clock;
    }
    public Map<String,Summary> summaries(Club c) {
        var result=new LinkedHashMap<String,Summary>();c.paymentProviders().forEach((key,value)->result.put(key,summary(key,value)));return result;
    }
    public Change snapshot() { var c=changes.club();return new Change(c.id(),summaries(c)); }
    public Summary summary(String name,Object entry) {
        Map<?,?> v=entry instanceof Map<?,?> m?m:Map.of();String pub=text(v,"publishableKey");
        boolean configured=switch(name) {
            case "STRIPE" -> present(v,"secretKeyEnc")&&present(v,"webhookSecretEnc")&&pub!=null&&present(v,"mode");
            case "SEPA_XML" -> present(v,"creditorName")&&present(v,"creditorId")&&(present(v,"ibanEnc")||present(v,"iban"));
            case "MANUAL" -> v.get("instructions") instanceof Map<?,?> m&&!m.isEmpty();default -> false;
        };
        String last="STRIPE".equals(name)?last4(pub):null;
        String masked="SEPA_XML".equals(name)?text(v,"ibanLast4"):null;
        if(masked==null&&"SEPA_XML".equals(name)) masked=last4(text(v,"iban"));
        Instant tested=v.get("lastTestAt") instanceof Instant i?i:v.get("lastTestAt") instanceof Date d?d.toInstant():null;
        return new Summary(PaymentProviderFlags.enabled(v),configured,masked==null?null:"****"+masked,last,text(v,"mode"),tested);
    }
    /**
     * PAY-10 (owner decision of 13 Nov 2026, S17 R-17-05): saving keys runs the connection test. This is that test for a Stripe save that
     * stores a new secret key, made with the new key before the save's transaction (network I/O never runs inside it, as {@link #test}):
     * the account it reaches, for {@link #update} to record. A key the provider refuses, or a provider that cannot be reached, → 422
     * STRIPE_CONNECTION_FAILED, and nothing is saved. Null when there is nothing to test (another provider, no new secret key) or when
     * the input is invalid on its own (mode, the key's prefix, the hosted demo's test mode), which {@link #update} refuses with 422
     * PROVIDER_CONFIG_INVALID without any call.
     */
    public ConsolePaymentAccess.Connection testKey(String name,Map<String,Object> input) {
        if(!"STRIPE".equals(name)) return null;
        String mode=text(input,"mode"),key=text(input,"secretKey");
        if(key==null||!Set.of("test","live").contains(Objects.toString(mode,""))||!key.startsWith("sk_"+mode+"_")||hostedDemo&&!"test".equals(mode)) return null;
        ConsolePaymentAccess.Connection result;
        try { result=provider.ping(mode,key); } catch(RuntimeException failure) { throw new ApiException(ErrorCode.STRIPE_CONNECTION_FAILED); }
        if(result==null||!result.ok()) throw new ApiException(ErrorCode.STRIPE_CONNECTION_FAILED);
        return result;
    }
    /** PAY-10: the `prod` profile, where saved live keys need {@link #TEST_MODE_OVERRIDE} to switch to test mode. */
    private boolean production() { return environment!=null&&environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod")); }
    /**
     * R-17-05. {@code tested} is {@link #testKey}'s result for this very input, run by the caller before its transaction; null makes a
     * save that stores a new Stripe secret key run the test here (club creation, whose keys come from its definition).
     */
    @Transactional
    @Audited(action=AuditAction.CLUB_PAYMENT_PROVIDER_CHANGED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Payment provider configuration'")
    public Change update(String name,Map<String,Object> input,ConsolePaymentAccess.Connection tested) {
        if (hostedDemo && "STRIPE".equals(name) && !"test".equals(input.get("mode"))) { throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID); }
        // R-17-05: the payment-method writers' lock first, so a disabling re-reads the provider's users under it.
        clubs.lockProviders(TenantContext.require());var old=changes.club();PlatformClubChanges.requireVersion(old,input.get("version"));
        var providers=new LinkedHashMap<>(old.paymentProviders());
        var settings=new LinkedHashMap<String,Object>();if(providers.get(name) instanceof Map<?,?> stored) stored.forEach((k,v)->settings.put(k.toString(),v));
        boolean enabled=input.get("enabled") instanceof Boolean b?b:PaymentProviderFlags.enabled(settings);
        settings.put("enabled",enabled);
        switch(name) {
            case "STRIPE" -> {
                String mode=text(input,"mode"),pub=text(input,"publishableKey");
                if(!Set.of("test","live").contains(Objects.toString(mode,""))||pub==null||!pub.startsWith("pk_"+mode+"_")) invalid();
                // PAY-10: on the prod profile, saved live keys switch to test mode only with the explicit override.
                if(production()&&"live".equals(settings.get("mode"))&&present(settings,"secretKeyEnc")&&"test".equals(mode)&&!Boolean.TRUE.equals(input.get(TEST_MODE_OVERRIDE)))
                    throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID,Map.of("field","mode","reason","LIVE_KEYS_SAVED"));
                if(settings.get("mode")!=null&&!mode.equals(settings.get("mode"))&&(!present(input,"secretKey")||!present(input,"webhookSecret"))) invalid();
                for(String field:List.of("secretKey","webhookSecret")) if(input.containsKey(field)&&input.get(field)!=null) {
                    String value=text(input,field);if(value==null||!value.startsWith(field.equals("secretKey")?"sk_"+mode+"_":"whsec_")) invalid();
                    settings.put(field+"Enc",vault.encrypt(value,old.id(),name,field+"Enc"));
                }
                if(!present(settings,"secretKeyEnc")||!present(settings,"webhookSecretEnc")) invalid();
                settings.put("mode",mode);settings.put("publishableKey",pub);settings.remove("lastTestAt");settings.remove("accountId");
                // PAY-10: a new secret key is saved only once its connection test passed, which is recorded as POST …/stripe/test records it.
                if(present(input,"secretKey")) {
                    var connection=tested!=null?tested:testKey(name,input);
                    if(connection==null) invalid();
                    settings.put("lastTestAt",clock.instant());settings.put("accountId",connection.accountId());
                }
            }
            case "SEPA_XML" -> {
                String iban=text(input,"iban"),creditor=text(input,"creditorId"),suffix=text(input,"suffix");
                vault.validateSepa(text(input,"creditorName"),creditor,iban,suffix);
                iban=iban.replaceAll("\\s","").toUpperCase(Locale.ROOT);
                settings.put("creditorName",text(input,"creditorName"));settings.put("creditorId",creditor.replaceAll("\\s","").toUpperCase(Locale.ROOT));settings.put("suffix",suffix);
                settings.put("ibanEnc",vault.encrypt(iban,old.id(),name,"ibanEnc"));settings.put("ibanLast4",last4(iban));settings.remove("iban");
            }
            case "MANUAL" -> {
                if(!(input.get("instructions") instanceof Map<?,?>)) invalid();
                @SuppressWarnings("unchecked") var text=(Map<String,String>)input.get("instructions");
                try { PlatformClubChanges.localized(text,old.locales(),old.defaultLocale()); } catch(ApiException invalid) { invalid(); }
                settings.put("instructions",text);
            }
            default -> invalid();
        }
        if(!enabled&&PaymentProviderFlags.enabled(old.paymentProviders().get(name))) requireUnused(old,name);
        providers.put(name,settings);changes.write(old,Map.of("paymentProviders",providers),"ClubPaymentProviderChanged",Map.of("provider",name,"configured",true));return snapshot();
    }
    @Transactional
    @Audited(action=AuditAction.CLUB_PAYMENT_PROVIDER_CHANGED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Payment provider removed'")
    public Change remove(String name,long version) {
        clubs.lockProviders(TenantContext.require());var old=changes.club();PlatformClubChanges.requireVersion(old,version);requireUnused(old,name);
        var providers=new LinkedHashMap<>(old.paymentProviders());providers.remove(name);
        changes.write(old,Map.of("paymentProviders",providers),"ClubPaymentProviderChanged",Map.of("provider",name,"configured",false));return snapshot();
    }
    private void requireUnused(Club club,String name) {
        long count=projection.providerUsers(club.id(),PaymentProviderFlags.method(name));
        if(count>0) throw new ApiException(ErrorCode.PROVIDER_IN_USE,Map.of("count",count));
    }
    /** Network I/O happens before the short versioned settlement transaction. A rotation makes settlement stale. */
    public ConsolePaymentAccess.Connection test() {
        var before=changes.club();ConsolePaymentAccess.Connection result;
        try { result=provider.ping(); } catch(RuntimeException failure) { throw new ApiException(ErrorCode.STRIPE_CONNECTION_FAILED); }
        if(!result.ok()) throw new ApiException(ErrorCode.STRIPE_CONNECTION_FAILED);
        var finalResult=result;
        transactions.executeWithoutResult(tx->{
            var current=changes.club();PlatformClubChanges.requireVersion(current,before.version());
            var all=new LinkedHashMap<>(current.paymentProviders());
            if(!(all.get("STRIPE") instanceof Map<?,?> config)) throw new ApiException(ErrorCode.STRIPE_CONNECTION_FAILED);
            var settings=new LinkedHashMap<String,Object>();config.forEach((k,v)->settings.put(k.toString(),v));
            // R-17-05: the account the keys reach, shown masked (`accountIdMasked`); a key rotation removes it with lastTestAt.
            settings.put("lastTestAt",clock.instant());settings.put("accountId",finalResult.accountId());all.put("STRIPE",settings);
            changes.write(current,Map.of("paymentProviders",all),"ClubUpdated",Map.of("provider","STRIPE","connectionTest",finalResult.ok()));
        });
        return result;
    }
    public static String text(Map<?,?> map,String key) { return map.get(key) instanceof String s&&!s.isBlank()?s:null; }
    private static boolean present(Map<?,?> map,String key) { return text(map,key)!=null; }
    private static String last4(String text) { return text==null?null:text.substring(Math.max(0,text.length()-4)); }
    private static void invalid() { throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID); }
}
