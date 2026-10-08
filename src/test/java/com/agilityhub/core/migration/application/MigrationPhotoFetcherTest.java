package com.agilityhub.core.migration.application;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MigrationPhotoFetcherTest {
    @Test void T_18_05_photoSourcesExcludePrivateAndSharedAddressRanges() throws Exception {
        for (String host : new String[]{"127.0.0.1", "0.0.0.0", "10.0.0.1", "169.254.0.1", "::1", "fc00::1", "fdff::1", "100.64.0.1", "100.127.255.255"}) {
            assertThat(MigrationPhotoFetcher.privateAddress(InetAddress.getByName(host))).as(host).isTrue();
        }
        for (String host : new String[]{"8.8.8.8", "2001:4860:4860::8888", "100.63.255.255", "100.128.0.1"}) {
            assertThat(MigrationPhotoFetcher.privateAddress(InetAddress.getByName(host))).as(host).isFalse();
        }
    }
}
