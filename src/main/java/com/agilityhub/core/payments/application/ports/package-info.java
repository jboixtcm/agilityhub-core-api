/**
 * Payment-owned integration contracts for pack opening, SEPA and card charging.
 * InactivityFeePort and LeaveBillingPort live in shared.application and are implemented by census.
 * Census owns its cancellation ports; bookings, training and activities implement them synchronously.
 * The booking pack and inactivity adapters live in bookings.application, preserving context direction.
 */
package com.agilityhub.core.payments.application.ports;
