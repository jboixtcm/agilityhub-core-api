# E11-T06 batch 10a triage — clubs.bookings.application (BookingEvents … ClassReminders), 66 rows

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| BookingViews:113 | classCard | NegateConditionalsMutator | SURVIVED | test: BookingViewsSurvivorsTest#T_08_09_staffSeeTheInstructorOnlyOfAClassThatHasOne |
| BookingViews:47 | classLabel | EmptyObjectReturnValsMutator | NO_COVERAGE | test: BookingViewsSurvivorsTest#T_08_47_aClassFilterValueIsLabelledWithItsLocalStartAndDescription |
| BookingViews:47 | lambda$classLabel$0 | EmptyObjectReturnValsMutator | NO_COVERAGE | test: BookingViewsSurvivorsTest#T_08_47_aClassFilterValueIsLabelledWithItsLocalStartAndDescription |
| BookingViews:151 | lambda$dog$7 | NullReturnValsMutator | NO_COVERAGE | reason: unreachable with real data — the IllegalStateException supplier only runs for a booking/entry whose dog is missing; dogs are never deleted (BookingMemberAccess.java:52-54 is a plain `findById`, no dog delete in clubs.census) and every booking/entry is created for an existing dog of its tenant |
| BookingQueryService:104 | dataset | VoidMethodCallMutator | SURVIVED | test: BookingQueryServiceSurvivorsTest#T_08_47_theListProjectsEveryStoredFieldOfARow |
| BookingQueryService:49 | lambda$reachable$1 | NullReturnValsMutator | NO_COVERAGE | test: BookingQueryServiceSurvivorsTest#T_08_26_theMemberReachOfAnUnknownBookingIsNotFound |
| BookingNotificationFacts:252 | activeAndFuture | BooleanTrueReturnValsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_15_17_onlyAnActiveBookingNotStartedYetIsStillRelevant |
| BookingNotificationFacts:111 | booking | NegateConditionalsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_27_n36TellsAClubBookingFromAClubCancellation |
| BookingNotificationFacts:252 | lambda$activeAndFuture$14 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_15_17_onlyAnActiveBookingNotStartedYetIsStillRelevant |
| BookingNotificationFacts:104 | lambda$booking$0 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_15_aMemberBookingTheirOwnDogGetsOneN04AndAGroupBookerASecondOne |
| BookingNotificationFacts:184 | lambda$seatTaken$5 | BooleanTrueReturnValsMutator | SURVIVED | reason: equivalent with real data — the mutant only skips the read-only early return when no live entry has `notifiedAt`; then none is NOTIFIED (WaitlistTransitions#notify always sets `notifiedAt`, WaitlistTransitions.java:72-73) and `lost` requires `notifiedAt` (line 189), so it returns the same `Optional.empty()` with no write and no further call |
| BookingNotificationFacts:185 | lambda$seatTaken$6 | NegateConditionalsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_20_aLostOfferAlreadyDemotedIsToldWithoutAnyWrite |
| BookingNotificationFacts:185 | lambda$seatTaken$6 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_20_aLostOfferAlreadyDemotedIsToldWithoutAnyWrite |
| BookingNotificationFacts:186 | lambda$seatTaken$7 | VoidMethodCallMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_20_anOfferStillNotifiedIsDemotedUnderTheSeatLockFirst |
| BookingNotificationFacts:186 | lambda$seatTaken$7 | EmptyObjectReturnValsMutator | SURVIVED | reason: discarded result — the supplier's value (the demoted ids of `demoteIfFull`) is returned by `transactions.write` and ignored at line 186 |
| BookingNotificationFacts:158 | lambda$stored$4 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_10_26_storingAnN19MarksTheNoticeSentOnlyWhenTheAppDeliveredIt |
| BookingNotificationFacts:232 | reminder | NegateConditionalsMutator | SURVIVED | reason: unreachable with real data — `ReminderDue` always carries the booking owner's `memberId` (RemindersJob.java:72-73), so the fallback is never used; the mutant differs only when the booking is missing (NPE), and bookings are never deleted (BR-12, Booking.java javadoc) |
| BookingNotificationFacts:234 | reminder | NegateConditionalsMutator | SURVIVED | reason: unreachable with real data — `ReminderDue` always carries the booking's `dogId` (RemindersJob.java:73), so the fallback is never used; the mutant differs only when the booking is missing (NPE), and bookings are never deleted (BR-12) |
| BookingNotificationFacts:235 | reminder | NegateConditionalsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_15_17_theClassReminderIsAboutTheBookedDog |
| BookingNotificationFacts:185 | seatTaken | NegateConditionalsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_08_20_aLostOfferAlreadyDemotedIsToldWithoutAnyWrite |
| BookingNotificationFacts:164 | sent | NegateConditionalsMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_10_26_anN19EmailAcceptedByTheProviderIsANoticeSent |
| BookingNotificationFacts:164 | sent | VoidMethodCallMutator | SURVIVED | test: BookingNotificationFactsSurvivorsTest#T_10_26_anN19EmailAcceptedByTheProviderIsANoticeSent |
| CensusBookingCancellations:34 | select | NegateConditionalsMutator | SURVIVED | test: CensusBookingCancellationsSurvivorsTest#E11_T06_aLeaveCancellationTakesTheSeatLockThenLocksTheClass |
| CensusBookingCancellations:35 | select | NegateConditionalsMutator | SURVIVED | test: CensusBookingCancellationsSurvivorsTest#E11_T06_aLeaveCancellationTakesTheSeatLockThenLocksTheClass |
| CensusBookingCancellations:34 | select | VoidMethodCallMutator | SURVIVED | test: CensusBookingCancellationsSurvivorsTest#E11_T06_aLeaveCancellationTakesTheSeatLockThenLocksTheClass |
| ClassBookingsAdapter:39 | byClass | VoidMethodCallMutator | SURVIVED | test: ClassBookingsAdapterSurvivorsTest#T_08_25_everyRequestedClassIsAKeyEvenWithoutLiveBookings |
| ClassBookingsAdapter:45 | cancelAllByClub | NullReturnValsMutator | NO_COVERAGE | test: ClassBookingsAdapterSurvivorsTest#T_08_25_aClubCancellationWithoutTextDelegatesToS08 |
| ClassBookingsAdapter:30 | clubCancelled | EmptyObjectReturnValsMutator | NO_COVERAGE | test: ClassBookingsAdapterSurvivorsTest#T_08_25_theClubCancelledBookingsOfAClassAreReadByState |
| ClassBookingsAdapter:27 | lambda$bookings$1 | EmptyObjectReturnValsMutator | SURVIVED | test: ClassBookingsAdapterSurvivorsTest#T_08_25_retainedBookingsAndEntriesComeBackInTheOrderOfTheIds |
| ClassBookingsAdapter:40 | lambda$byClass$3 | EmptyObjectReturnValsMutator | NO_COVERAGE | reason: unreachable with real data — `forClasses` returns only bookings whose `classSessionId` is in `classIds` (BookingRepository.java:40) and line 39 already put every one of them, so `computeIfAbsent` never creates a list |
| ClassBookingsAdapter:23 | lambda$waitlistEntries$0 | EmptyObjectReturnValsMutator | NO_COVERAGE | test: ClassBookingsAdapterSurvivorsTest#T_08_25_retainedBookingsAndEntriesComeBackInTheOrderOfTheIds |
| ClassBookingsAdapter:52 | ref | NegateConditionalsMutator | SURVIVED | test: ClassBookingsAdapterSurvivorsTest#T_08_25_aRefTellsWhetherAPackPaidTheSeat |
| BookingTransactions:38 | lambda$write$0 | EmptyObjectReturnValsMutator | SURVIVED | test: BookingTransactionsSurvivorsTest#T_08_29_eachClassTouchedHoldsItsOwnLane |
| BookingTransactions:41 | retry | IncrementsMutator | TIMED_OUT | test: BookingTransactionsSurvivorsTest#T_08_29_aConflictIsRetriedAtMostThreeTimesAndEveryRetryIsCounted (bounded fake: a 4th attempt throws AssertionError) |
| BookingTransactions:46 | retry | VoidMethodCallMutator | TIMED_OUT | test: BookingTransactionsSurvivorsTest#T_08_29_aConflictIsRetriedAtMostThreeTimesAndEveryRetryIsCounted (bounded fake; asserts 2 counted retries) |
| BookingTransactions:47 | retry | VoidMethodCallMutator | SURVIVED | test: BookingTransactionsSurvivorsTest#T_08_29_anInterruptedBackoffStopsRetryingAndKeepsTheInterrupt |
| BookingTransactions:48 | retry | VoidMethodCallMutator | NO_COVERAGE | test: BookingTransactionsSurvivorsTest#T_08_29_anInterruptedBackoffStopsRetryingAndKeepsTheInterrupt |
| BookingOwners:15 | cancelled | BooleanFalseReturnValsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_18_onlyAnInTimeMemberCancellationIsCancelled |
| BookingOwners:23 | checkout | EmptyObjectReturnValsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_24_aPendingPayToBookBookingOffersItsOpenCheckout |
| BookingOwners:15 | lambda$cancelled$0 | NegateConditionalsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_18_onlyAnInTimeMemberCancellationIsCancelled |
| BookingOwners:15 | lambda$cancelled$0 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_18_onlyAnInTimeMemberCancellationIsCancelled |
| BookingOwners:18 | lambda$cancelledBeforeConfirmation$1 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_24_cancelledBeforeConfirmationNeedsACancelledUnpaidPayToBookBooking |
| BookingOwners:19 | lambda$cancelledBeforeConfirmation$2 | BooleanTrueReturnValsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_24_cancelledBeforeConfirmationNeedsACancelledUnpaidPayToBookBooking |
| BookingOwners:23 | lambda$checkout$4 | NegateConditionalsMutator | SURVIVED | test: BookingOwnersSurvivorsTest#T_08_24_aPendingPayToBookBookingOffersItsOpenCheckout |
| BookingOwners:23 | lambda$checkout$4 | BooleanTrueReturnValsMutator | SURVIVED | reason: unreachable with real data — only a PAYMENT_PENDING booking carries `checkoutUrl`: written only while PAYMENT_PENDING (BookingConfirmationService.java:135) and cleared when it leaves that state (BookingConfirmationService.java:177; BookingCancellationService.java:138,180 via `Booking.Charge#settled`), so the next filter (line 24) drops every other state anyway |
| BookingOwners:24 | lambda$checkout$5 | NegateConditionalsMutator | NO_COVERAGE | test: BookingOwnersSurvivorsTest#T_08_24_aPendingPayToBookBookingOffersItsOpenCheckout |
| BookingOwners:24 | lambda$checkout$6 | NullReturnValsMutator | NO_COVERAGE | test: BookingOwnersSurvivorsTest#T_08_24_aPendingPayToBookBookingOffersItsOpenCheckout |
| ClaimNoShowCommand:21 | name | EmptyObjectReturnValsMutator | NO_COVERAGE | test: ClaimNoShowCommandSurvivorsTest#T_10_26_theCommandIsTheBinCoreVerb |
| ClaimNoShowCommand:24 | run | NegateConditionalsMutator | SURVIVED | test: ClaimNoShowCommandSurvivorsTest#T_10_26_withoutAClubEveryActiveClubIsClaimedAndReported |
| ClaimNoShowCommand:32 | run | NegateConditionalsMutator | SURVIVED | test: ClaimNoShowCommandSurvivorsTest#T_10_26_withoutAClubEveryActiveClubIsClaimedAndReported |
| ClaimNoShowCommand:32 | run | VoidMethodCallMutator | SURVIVED | test: ClaimNoShowCommandSurvivorsTest#T_10_26_withoutAClubEveryActiveClubIsClaimedAndReported |
| ClassReminders:24 | lambda$current$0 | BooleanTrueReturnValsMutator | SURVIVED | test: ClassRemindersSurvivorsTest#T_15_17_onlyAnActiveBookingNotRemindedYetIsCurrent |
| ClassReminders:26 | markSent | BooleanTrueReturnValsMutator | SURVIVED | test: ClassRemindersSurvivorsTest#T_15_17_markSentReportsWhetherTheMarkWasWritten |
| BookingsAutoConfiguration:59 | noActivityFinishing | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutActivitiesNothingIsEverFinished |
| BookingsAutoConfiguration:55 | noActivityHistory | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutTrainingOrActivitiesTheHistoriesAreEmpty |
| BookingsAutoConfiguration:41 | noFollowup | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutFollowupTheDogHasNoTasksAndTheSheetLeavesThemOut |
| BookingsAutoConfiguration:51 | noTrainingHistory | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutTrainingOrActivitiesTheHistoriesAreEmpty |
| BookingsAutoConfiguration:53 | noTrainingStats | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutTrainingOrActivitiesTheHistoriesAreEmpty |
| BookingEvents:27 | belowMinimum | NegateConditionalsMutator | SURVIVED | test: BookingEventsSurvivorsTest#T_15_13_classBelowMinimumCarriesTheCancellingMemberAndTheCatalogPayload |
| BookingEvents:26 | belowMinimum | EmptyObjectReturnValsMutator | SURVIVED | reason: discarded result — the only caller ignores the outbox id (BookingCounters.java:36); `BookingEvents` implements no port |
| BookingEvents:40 | loggingBlocked | NegateConditionalsMutator | SURVIVED | test: BookingEventsSurvivorsTest#T_08_23_onlyABlockedAttemptIsLoggedWithTheRequestActor |
| BookingEvents:22 | publish | NegateConditionalsMutator | SURVIVED | test: BookingEventsSurvivorsTest#T_08_27_anEventCarriesTheActingAccountUnlessTheSystemActs |
| BookingEvents:21 | publish | EmptyObjectReturnValsMutator | SURVIVED | reason: discarded result — every caller (BookingCancellationService, BookingConfirmationService, SeatHoldService, WaitlistService, WaitlistTransitions) calls `events.publish(…)` as a statement and ignores the outbox id; `BookingEvents` implements no port |
| BookingsAutoConfiguration$1:42 | available | BooleanTrueReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutFollowupTheDogHasNoTasksAndTheSheetLeavesThemOut |
| BookingsAutoConfiguration$1:45 | tasks | NullReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutFollowupTheDogHasNoTasksAndTheSheetLeavesThemOut |
| BookingsAutoConfiguration$2:61 | finish | BooleanTrueReturnValsMutator | NO_COVERAGE | test: BookingsAutoConfigurationSurvivorsTest#E11_T06_withoutActivitiesNothingIsEverFinished |
