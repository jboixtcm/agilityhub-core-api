package com.agilityhub.core.shared.application.lists;

import java.util.Set;

public interface ListProvider {
    Set<String> keys();
    ListDataset dataset(String key);
}
