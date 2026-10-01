package com.agilityhub.core.payments.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S12 R-12-16 channels of a payment marked by hand (and of the MANUAL payment method, model §3). */
@Schema(enumAsRef = true)
public enum ManualChannel { CASH, TRANSFER, BIZUM }
