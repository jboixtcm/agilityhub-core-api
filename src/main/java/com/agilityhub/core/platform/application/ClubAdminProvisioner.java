package com.agilityhub.core.platform.application;

import java.util.List;

/** Identity implements this port; every operation uses the current tenant. */
public interface ClubAdminProvisioner {
    record Admin(String email, String name, String locale) { }
    boolean needsProvision(Admin admin);
    void provision(Admin admin);
    List<Admin> list();
}
