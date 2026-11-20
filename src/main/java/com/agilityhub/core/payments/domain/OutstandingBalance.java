package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.Money;

public record OutstandingBalance(String memberId, String memberName, Money invoices, Money upfront, Money processing) { }
