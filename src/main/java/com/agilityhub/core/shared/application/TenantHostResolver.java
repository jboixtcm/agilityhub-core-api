package com.agilityhub.core.shared.application;

import java.util.Optional;

public interface TenantHostResolver { Optional<String> resolve(String host); }
