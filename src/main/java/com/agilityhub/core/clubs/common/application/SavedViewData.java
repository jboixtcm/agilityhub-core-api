package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import java.util.List;

public record SavedViewData(String id, String ownerAccountId, String listKey, String name,
        List<String> columns, List<Filter> filters, List<String> sort, boolean shared, long version) { }
