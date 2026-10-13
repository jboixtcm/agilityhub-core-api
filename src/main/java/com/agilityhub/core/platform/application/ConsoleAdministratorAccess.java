package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import java.util.*;

/** Identity owns the implementation; platform reads only the administrator configuration. */
public interface ConsoleAdministratorAccess {
    record Admin(String accountId,String name,String email,String membershipStatus,Instant lastAccessAt) { }
    record Change(String id,@AuditField List<Map<String,String>> admins,Admin admin) { }
    List<Admin> list();
    Change invite(String email,String name);
    Change remove(String accountId);
}
