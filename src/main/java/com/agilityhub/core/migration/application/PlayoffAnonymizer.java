package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.shared.domain.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One-use HMAC key is supplied by the operator, never written into the derivative. */
public final class PlayoffAnonymizer {
    private final byte[] key;
    public PlayoffAnonymizer(String key) {
        if (key == null || key.length() < 32) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }
    private String number(String kind, String value, int digits) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            var n = new BigInteger(1,mac.doFinal((kind + ":" + value.strip().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));
            return String.format(Locale.ROOT,"%0" + digits + "d",n.mod(BigInteger.TEN.pow(digits)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    public String replace(String kind, String value) {
        if (value.isBlank() || kind.equals("keep")) { return value; }
        String n = number(kind,value,8);
        return switch (kind) {
            case "id" -> "P" + number("id",value,16);
            case "number" -> "9" + number(kind,value,7);
            case "name" -> "Example " + n;
            case "surname" -> {
                var matcher = java.util.regex.Pattern.compile("\\(([^()]*)\\)").matcher(value);
                String dog = matcher.find() ? " (" + replace("dog",matcher.group(1)) + ")" : "";
                yield "Surname " + number(kind,value.replaceAll("\\([^()]*\\)", "").strip(),8) + dog;
            }
            case "dog" -> "Dog " + n;
            case "document" -> n + "TRWAGMYFPDXBNJZSQVHLCKE".charAt(Integer.parseInt(n)%23);
            case "passport" -> "TEST" + n;
            case "phone" -> "+346" + n;
            case "postal" -> "08001";
            case "address" -> "Example place " + n;
            case "email" -> "person" + number("email",value,16) + "@example.test";
            case "iban" -> {
                String compact = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
                if (!validIban(compact)) { yield "INVALID_TEST_IBAN"; }
                String domestic = "9999" + number(kind,compact,16);
                int checksum = 98 - new BigInteger(domestic + "142800").mod(BigInteger.valueOf(97)).intValue();
                yield "ES" + String.format(Locale.ROOT,"%02d",checksum) + domestic;
            }
            case "chip" -> number(kind,value,15);
            case "license" -> "TEST" + n;
            default -> "[redacted]";
        };
    }
    public static boolean validIban(String value) {
        if (!value.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) { return false; }
        String rotated = value.substring(4)+value.substring(0,4); var digits = new StringBuilder();
        for (char ch : rotated.toCharArray()) { if (Character.isDigit(ch)) { digits.append(ch); } else { digits.append(ch-'A'+10); } }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue()==1;
    }
    public void anonymize(Path dir, Path out, MappingConfig mapping) {
        if (dir.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize())) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var input = PlayoffInput.read(dir,mapping);
        if (input.incidents().stream().anyMatch(r -> r.outcome().equals("ERROR"))) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
        try {
            Files.createDirectories(out);
            for (var schema : mapping.files().values()) {
                Path source = dir.resolve(schema.name()); if (!Files.isRegularFile(source)) { continue; }
                var table = PlayoffTable.read(source); var result = new ArrayList<List<String>>(); result.add(table.getFirst());
                for (int r=1;r<table.size();r++) {
                    var row = new ArrayList<>(Collections.nCopies(table.get(r).size(),"[redacted]"));
                    for (var column : schema.columns()) { row.set(column.at()-1,replace(column.anonymize(),table.get(r).get(column.at()-1))); }
                    result.add(row);
                }
                Path target = out.resolve(schema.name());
                if (Files.exists(target)) { throw new ApiException(ErrorCode.INVALID_STATE); }
                PlayoffTable.write(target,result);
            }
        } catch (IOException failure) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
}
