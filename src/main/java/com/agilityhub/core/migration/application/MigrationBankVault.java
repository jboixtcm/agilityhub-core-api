package com.agilityhub.core.migration.application;

import com.agilityhub.core.shared.domain.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypted handoff to S12; no mandate is issued by the census importer. */
@Component
public class MigrationBankVault {
    private final String key;
    public MigrationBankVault(@Value("${core.migration.bank-key:}") String key) { this.key=key; }
    public void requireKey() { key(); }
    private byte[] key() {
        try { byte[] bytes=Base64.getDecoder().decode(key); if (bytes.length!=32) { throw new IllegalArgumentException(); } return bytes; }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
    }
    public String encrypt(String iban,String clubId,String memberId) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((clubId+":"+memberId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(iban.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Bank encryption failed"); }
    }
}
