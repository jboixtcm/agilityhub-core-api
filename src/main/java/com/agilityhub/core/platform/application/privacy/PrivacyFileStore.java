package com.agilityhub.core.platform.application.privacy;

import java.io.InputStream;

/**
 * A file store that holds members' files (S14 R-14-13 `fitxers/`, R-14-15 `filesPending[]`): the attachments store of the dog
 * files and the exports store. `delete` of a missing key is not an error (the cleanup retries).
 */
public interface PrivacyFileStore {
    String store();
    InputStream read(String key);
    void delete(String key);
}
