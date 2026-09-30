package com.agilityhub.core.clubs.messaging.support;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * E7-T03 round 2 (review #4): the push keys of the fixtures, as a browser gives them — a freshly generated P-256 public key
 * (uncompressed `04 ‖ x ‖ y`, base64url) and a 16-byte `auth` secret — plus two points that are not keys: `(0, 0)` and a point
 * off the curve. `ROUTE_P256DH` is the fixed valid key of `e7-routes.json` (`roadmap/evidence/E7-T03/p256_fixture_key.py`).
 */
public final class PushKeyFixtures {
    public static final String ROUTE_P256DH = "BHOSs0YsSaLk6TQZCHSDATUjNlrmU0Lvhn21nxNNQtPGmW1zAZmQRcHOFqToHw0VllmplTaYYZl3k2c-1Jjfrp8";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private PushKeyFixtures() { }

    /** A new P-256 public key, base64url of the 65-byte uncompressed point. */
    public static String p256dh() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var point = ((ECPublicKey) generator.generateKeyPair().getPublic()).getW();
            return point(point.getAffineX(), point.getAffineY());
        } catch (java.security.GeneralSecurityException missing) { throw new IllegalStateException(missing); }
    }
    /** A new 16-byte `auth` secret, base64url. */
    public static String auth() {
        var secret = new byte[16]; new java.security.SecureRandom().nextBytes(secret);
        return URL.encodeToString(secret);
    }
    /** `04 ‖ 0 ‖ 0`: the right length and prefix, not a point of the curve. */
    public static String zeroPoint() { return point(BigInteger.ZERO, BigInteger.ZERO); }
    /** A valid key with its y moved by one: the right length and prefix, off the curve. */
    public static String offCurve() {
        var valid = Base64.getUrlDecoder().decode(p256dh());
        valid[64] ^= 0x01;
        return URL.encodeToString(valid);
    }
    /** The uncompressed point `04 ‖ x ‖ y` of two coordinates, each on 32 bytes. */
    public static String point(BigInteger x, BigInteger y) {
        var bytes = new byte[65]; bytes[0] = 0x04;
        copy(x, bytes, 1); copy(y, bytes, 33);
        return URL.encodeToString(bytes);
    }
    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] raw = value.toByteArray();
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, target, offset + 32 - length, length);
    }
}
