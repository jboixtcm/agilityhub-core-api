# E11-T06 batch 10 (part b) — DemoAttendanceSeeder, DemoBillingBookingsSeeder, DemoBookingSeeder, DemoMembers, DemoScenarioSeeder, HistoryQuery: 53 rows, 48 killed by staged unit tests, 5 reasons

| Class:line | Method | Mutator | Status | Decision |
|---|---|---|---|---|
| DemoBookingSeeder:65 | apply | ConditionalsBoundaryMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_seed42PicksTheSameRegistrantAndLeavesTheCapacityAloneWithoutWaitingRows |
| DemoBookingSeeder:66 | apply | ConditionalsBoundaryMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aClassAlreadyFullByItsBookingsTakesItsWaitingEntriesWithoutACapacityEdit |
| DemoBookingSeeder:54 | apply | MathMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_seed42PicksTheSameRegistrantAndLeavesTheCapacityAloneWithoutWaitingRows |
| DemoBookingSeeder:58 | apply | MathMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aTooSmallPoolForBookedPlusWaitingFailsWithNotFound |
| DemoBookingSeeder:62 | apply | NegateConditionalsMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aWithPackRegistrantPreparesAPackBeforeBooking |
| DemoBookingSeeder:69 | apply | NegateConditionalsMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aRowWithFreeSeatsIsHeldAtItsBookedCountForTheWaitingEntriesAndGetsItsAutoCapacityBack |
| DemoBookingSeeder:69 | apply | NegateConditionalsMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aRowWithFreeSeatsIsHeldAtItsBookedCountForTheWaitingEntriesAndGetsItsAutoCapacityBack |
| DemoBookingSeeder:69 | apply | VoidMethodCallMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aRowWithFreeSeatsIsHeldAtItsBookedCountForTheWaitingEntriesAndGetsItsAutoCapacityBack |
| DemoBookingSeeder:41 | apply | EmptyObjectReturnValsMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aSeedWithoutBookingRowsStillAnswersBothCounters |
| DemoBookingSeeder:82 | bookingInstant | NegateConditionalsMutator | NO_COVERAGE | test: DemoBookingSeederSurvivorsTest#E11_T06_theClassIsBookedAtTheRunInstantOnceItsBookingWeekIsOpen |
| DemoBookingSeeder:49 | lambda$apply$1 | BooleanTrueReturnValsMutator | SURVIVED | test: DemoBookingSeederSurvivorsTest#E11_T06_aReanchoredRunKeepsAGeneratedDraftClassWithoutBookingIt |
| DemoBookingSeeder:52 | lambda$apply$2 | NullReturnValsMutator | NO_COVERAGE | test: DemoBookingSeederSurvivorsTest#E11_T06_aRowWhoseClassDoesNotExistFailsWithNotFound |
| HistoryQuery:96 | classItems | NegateConditionalsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_07_aNoticeAfterTheClassEndShowsTheBookingAsCancelledLate |
| HistoryQuery:57 | history | NegateConditionalsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_33_withLevelsEnabledEachDogCarriesItsLevelCode |
| HistoryQuery:69 | history | NegateConditionalsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_aTrainingTheMemberCancelledCarriesTheByMemberDetail |
| HistoryQuery:53 | history | VoidMethodCallMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_eachClassRowCarriesItsDogNameAndNoSortKey |
| HistoryQuery:79 | history | VoidMethodCallMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_eachClassRowCarriesItsDogNameAndNoSortKey |
| HistoryQuery:48 | lambda$history$0 | BooleanTrueReturnValsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_aPendingDogIsLeftOutOfTheHistory |
| HistoryQuery:49 | lambda$history$1 | BooleanTrueReturnValsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_ownDogsComeFirstAndTheFamilyOwnersAreReadOnce |
| HistoryQuery:51 | lambda$history$2 | BooleanTrueReturnValsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_aDogTheMemberCannotReachIsRefused |
| DemoScenarioSeeder:86 | apply | MathMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_seed42ShufflesEachClassRowWithItsOwnIndex |
| DemoScenarioSeeder:86 | apply | MathMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_seed42ShufflesEachClassRowWithItsOwnIndex |
| DemoScenarioSeeder:95 | lambda$apply$4 | MathMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_aClassRowThePoolCannotFillForBookedPlusWaitingFailsWithNotFound |
| DemoScenarioSeeder:92 | lambda$apply$4 | VoidMethodCallMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_seed42ShufflesEachClassRowWithItsOwnIndex |
| DemoScenarioSeeder:122 | lambda$offer$8 | BooleanTrueReturnValsMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_aCancellationWithOnlyAnOpenOfferMakesNoNewOffer |
| DemoScenarioSeeder:123 | lambda$offer$9 | EmptyObjectReturnValsMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_aCancellationOffersExactlyTheFreedSeatAndCountsTheOffersMade |
| DemoScenarioSeeder:130 | lambda$slot$10 | NullReturnValsMutator | NO_COVERAGE | test: DemoScenarioSeederSurvivorsTest#E11_T06_aClassRowWhoseClassDoesNotExistFailsWithNotFound |
| DemoScenarioSeeder:123 | offer | ConditionalsBoundaryMutator | SURVIVED | reason: unreachable with real data — `offer` runs right after the cancelled booking left LIVE, so `free = capacity − live ≥ 1` unless the class was over capacity, which S06/S08 refuse (ClassSessionService.java:109 CAPACITY_BELOW_BOOKINGS, ClassSessionBookingAccess.java:85 CLASS_FULL); `free == 0` never reaches DemoScenarioSeeder.java:123 |
| DemoScenarioSeeder:121 | offer | MathMutator | SURVIVED | test: DemoScenarioSeederSurvivorsTest#E11_T06_aCancellationOffersExactlyTheFreedSeatAndCountsTheOffersMade |
| DemoAttendanceSeeder:73 | apply | NegateConditionalsMutator | TIMED_OUT | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aScenarioWithoutAnAttendanceSectionAnswersEveryCountAtZero (the mutant converts the absent section to a null spec and fails fast with an exception, no loop) |
| DemoAttendanceSeeder:71 | apply | VoidMethodCallMutator | SURVIVED | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aScenarioWithoutAnAttendanceSectionAnswersEveryCountAtZero |
| DemoAttendanceSeeder:73 | apply | EmptyObjectReturnValsMutator | SURVIVED | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aScenarioWithoutAnAttendanceSectionAnswersEveryCountAtZero |
| DemoAttendanceSeeder:77 | lambda$apply$1 | NullReturnValsMutator | NO_COVERAGE | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aMissingSheetClassFailsWithNotFoundNamingTheSlot |
| DemoAttendanceSeeder:82 | lambda$apply$2 | NullReturnValsMutator | NO_COVERAGE | test: DemoAttendanceSeederSurvivorsTest#E11_T06_anInstructorWithoutACensusMemberFailsWithNotFound |
| DemoAttendanceSeeder:111 | lambda$history$4 | BooleanTrueReturnValsMutator | SURVIVED | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aHistoryClassLeftDraftIsNotFoundAndNobodyBooksIt |
| DemoAttendanceSeeder:112 | lambda$history$5 | NullReturnValsMutator | NO_COVERAGE | test: DemoAttendanceSeederSurvivorsTest#E11_T06_aMissingHistoryClassFailsWithNotFoundNamingTheDate |
| DemoAttendanceSeeder:139 | lambda$history$7 | NullReturnValsMutator | SURVIVED | reason: discarded result — the `Claim` the supplier returns only passes through `context.asOf`, whose value the statement at DemoAttendanceSeeder.java:139 ignores; the claim's writes and outbox event happen inside `claims.claim` either way |
| DemoAttendanceSeeder:154 | lambda$mark$11 | EmptyObjectReturnValsMutator | SURVIVED | reason: discarded result — `mark` is void and drops what `DemoSeedActor.as` returns (DemoAttendanceSeeder.java:154); the sheet save already happened inside the inner supplier |
| DemoAttendanceSeeder:156 | lambda$mark$9 | NullReturnValsMutator | NO_COVERAGE | reason: unreachable with real data — `mark`'s only callers (DemoAttendanceSeeder.java:132,134) pass the `classId` that `sessions.slot` resolved as an ACTIVE class at :111 and `classes.require` read again at :113, in the same seed run (DemoPlanningService.java:43,48 `@Transactional`); a cancelled-by-club class `continue`s at :123 before any mark; `InstructorScheduleAccess.find` reads that class by id in the same tenant (InstructorScheduleAccess.java:37) and class sessions are never deleted (no `remove`/`deleteById` of `class_sessions` in src/main, only template classes are deleted, TemplateService.java:108), so the `orElseThrow` supplier never runs |
| DemoAttendanceSeeder:166 | opening | MathMutator | SURVIVED | test: DemoAttendanceSeederSurvivorsTest#E11_T06_theSheetCastBooksOneSecondAfterTheOpeningOfItsBookingWeek |
| DemoMembers:87 | join | NullReturnValsMutator | SURVIVED | test: DemoMembersSurvivorsTest#E11_T06_joinReturnsTheWaitingEntryCreatedAsTheMember |
| DemoMembers:87 | lambda$join$11 | NullReturnValsMutator | SURVIVED | test: DemoMembersSurvivorsTest#E11_T06_joinReturnsTheWaitingEntryCreatedAsTheMember |
| DemoMembers:87 | lambda$join$12 | NullReturnValsMutator | SURVIVED | test: DemoMembersSurvivorsTest#E11_T06_joinReturnsTheWaitingEntryCreatedAsTheMember |
| DemoMembers:55 | lambda$member$5 | NullReturnValsMutator | NO_COVERAGE | test: DemoMembersSurvivorsTest#E11_T06_aMemberWithoutAnActiveDogOfTheLevelFailsWithNotFound |
| DemoMembers:42 | lambda$pool$1 | EmptyObjectReturnValsMutator | TIMED_OUT | test: DemoMembersSurvivorsTest#E11_T06_thePoolFollowsTheMemberNumberAsANumberNotAsText (fails fast on the order assertion: "10" sorts before "9" as text) |
| DemoMembers:45 | lambda$pool$2 | BooleanTrueReturnValsMutator | SURVIVED | test: DemoMembersSurvivorsTest#E11_T06_thePoolLeavesOutDogsThatAreNotActive |
| DemoMembers:68 | lambda$preparePack$6 | NullReturnValsMutator | NO_COVERAGE | test: DemoMembersSurvivorsTest#E11_T06_aWithPackRowInAClubWithoutAnActivePackPlanFailsWithTheSeedMessage |
| DemoMembers:74 | lambda$preparePack$8 | NullReturnValsMutator | NO_COVERAGE | test: DemoMembersSurvivorsTest#E11_T06_aPackMemberWithoutBalanceAndNoActivePackPlanFailsWithTheSeedMessage |
| DemoMembers:73 | preparePack | NegateConditionalsMutator | SURVIVED | test: DemoMembersSurvivorsTest#E11_T06_aBookingOfAMemberWithoutAPackPlanNeverOpensAPack |
| HistoryQuery:55 | lambda$history$4 | BooleanTrueReturnValsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_ownDogsComeFirstAndTheFamilyOwnersAreReadOnce |
| HistoryQuery:77 | lambda$history$7 | EmptyObjectReturnValsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_19_rowsStartingAtTheSameInstantAreOrderedById |
| HistoryQuery:83 | lambda$history$9 | NegateConditionalsMutator | SURVIVED | test: HistoryQuerySurvivorsTest#T_10_33_withLevelsEnabledEachDogCarriesItsLevelCode |
| DemoBillingBookingsSeeder:39 | lambda$seed$0 | EmptyObjectReturnValsMutator | SURVIVED | reason: discarded result — the map `attendance.save` returns only passes through `context.asOf`, and DemoBillingBookingsSeeder.java:39 ignores it; the save and its events happen either way |
