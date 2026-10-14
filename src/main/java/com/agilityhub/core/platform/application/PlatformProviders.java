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
    public record Summary(boolean enabled,boolean configured,String masked,String last4,String mode,Instant lastTestAt) { }
    public record Change(String id,@AuditField Map<String,Summary> providers) { }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
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
    @Transactional
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Payment provider configuration'")
    public Change update(String name,Map<String,Object> input) {
        var old=changes.club();PlatformClubChanges.requireVersion(old,input.get("version"));
        var providers=new LinkedHashMap<>(old.paymentProviders());
        var settings=new LinkedHashMap<String,Object>();if(providers.get(name) instanceof Map<?,?> stored) stored.forEach((k,v)->settings.put(k.toString(),v));
        boolean enabled=input.get("enabled") instanceof Boolean b?b:PaymentProviderFlags.enabled(settings);
        settings.put("enabled",enabled);
        switch(name) {
            case "STRIPE" -> {
                String mode=text(input,"mode"),pub=text(input,"publishableKey");
                if(!Set.of("test","live").contains(Objects.toString(mode,""))||pub==null||!pub.startsWith("pk_"+mode+"_")) invalid();
                if(settings.get("mode")!=null&&!mode.equals(settings.get("mode"))&&(!present(input,"secretKey")||!present(input,"webhookSecret"))) invalid();
                for(String field:List.of("secretKey","webhookSecret")) if(input.containsKey(field)&&input.get(field)!=null) {
                    String value=text(input,field);if(value==null||!value.startsWith(field.equals("secretKey")?"sk_"+mode+"_":"whsec_")) invalid();
                    settings.put(field+"Enc",vault.encrypt(value,old.id(),name,field+"Enc"));
                }
                if(!present(settings,"secretKeyEnc")||!present(settings,"webhookSecretEnc")) invalid();
                settings.put("mode",mode);settings.put("publishableKey",pub);settings.remove("lastTestAt");settings.remove("accountId");
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
    @Audited(action=AuditAction.CLUB_UPDATED,entityType="'Club'",entity="#result.id",before="snapshot()",reason="'Payment provider removed'")
    public Change remove(String name,long version) {
        var old=changes.club();PlatformClubChanges.requireVersion(old,version);requireUnused(old,name);
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
            settings.put("lastTestAt",clock.instant());all.put("STRIPE",settings);
            changes.write(current,Map.of("paymentProviders",all),"ClubUpdated",Map.of("provider","STRIPE","connectionTest",finalResult.ok()));
        });
        return result;
    }
    public static String text(Map<?,?> map,String key) { return map.get(key) instanceof String s&&!s.isBlank()?s:null; }
    private static boolean present(Map<?,?> map,String key) { return text(map,key)!=null; }
    private static String last4(String text) { return text==null?null:text.substring(Math.max(0,text.length()-4)); }
    private static void invalid() { throw new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID); }
}
