package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import java.util.List;

public record SavedViewData(String id, String ownerAccountId, String listKey, String name,
        List<String> columns, List<Filter> filters, List<String> sort, boolean shared, long version) {
    /** The owner of the views the platform seeds (S13 «Baixes previstes»): one constant for the seeder and the predicate (INC-57). */
    public static final String SYSTEM_OWNER = "SYSTEM";
    public boolean system() { return SYSTEM_OWNER.equals(ownerAccountId); }
}
