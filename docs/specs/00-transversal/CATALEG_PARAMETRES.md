# Catàleg de paràmetres (PARAMETER)

**v1.0 · 03-09-2026** · Cap valor de negoci fora d'aquest catàleg. Cada clau té valor per defecte de producte al codi (`ParameterCatalog`), override per club (col·lecció `parameters`, amb històric) i tests de les branques que canvia. Els valors de la columna «Cànic» són els dels mockups/D11 (v1.6). «Bloc D11» = on s'edita al backoffice; `sistema` = no visible (només consola/seed).

Tipus: `int` · `bool` · `enum` · `time` (HH:mm local) · `duration` (minuts) · `money` · `localizedText` · `json` · `list`. Àmbit: `club` (per defecte) · `ring` · `level` (override per catàleg).

## Accés i sessió (`auth.*`) — bloc sistema
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `auth.sessionDays` | int | 30 | refresh token lliscant (ADR-004) |
| `auth.magicLinkMinutes` | int | 15 | caducitat de l'enllaç màgic |
| `auth.impersonationMinutes` | int | 60 | «Entra com l'abonat» |
| `auth.passwordMinLength` | int | 8 | |

## Alta (`signup.*`) — bloc Alta i consentiments
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `signup.enabled` | bool | true | tanca el formulari públic (mostra `signup.text.closed`) |
| `signup.pendingExpiryDays` | int | 30 | PAR-17: recordatori a l'admin, no esborra |
| `signup.requireDogDocumentAtSignup` | bool | false | la cartilla no bloqueja l'alta (R24-08) |
| `signup.firstMonthSplitDay` | int | 16 | quota inicial: de l'1 al 15 «mes sencer / des del 16 mig mes» (R18-08) |
| `signup.allowFamilyGroupPending` | bool | true | «Deixa-ho pendent i continua» |
| `signup.text.paymentDay` | localizedText | `{"ca": "El rebut es carrega normalment l'últim dia de cada mes. Tens fins al dia {deadlineDay} per modificar l'emissió de rebuts del mes següent, des de la teva fitxa o posant-te en contacte amb el club.", "es": "El recibo se carga normalmente el último día de cada mes. Tienes hasta el día {deadlineDay} para modificar la emisión de recibos del mes siguiente, desde tu ficha o poniéndote en contacto con el club.", "en": "Payment is normally collected on the last day of each month. You have until day {deadlineDay} to change billing for the following month, from your profile or by contacting the club."}` | pantalla 19 |
| `signup.text.cashConditions` | localizedText | `{"ca": "Pagament en efectiu: la quota es paga mensualment al club. Posa't en contacte amb el club per coordinar-ho.", "es": "Pago en efectivo: la cuota se paga mensualmente en el club. Ponte en contacto con el club para coordinarlo.", "en": "Cash payment: the fee is paid monthly at the club. Contact the club to arrange this."}` | 19 |
| `signup.text.freeTrainingConditions` | localizedText | `{"ca": "Les classes són sempre amb instructor i se'n poden fer fins a dues per setmana. A més hi ha la possibilitat d'entrenament lliure fora d'hores de classe a partir de nivell D amb llicència esportiva.", "es": "Las clases son siempre con instructor y se pueden hacer hasta dos por semana. Además, existe la posibilidad de entrenamiento libre fuera del horario de clases a partir del nivel D con licencia deportiva.", "en": "Classes always have an instructor, with up to two classes per week. Free training outside class hours is also available from level D with a sports licence."}` | 17 (contingut de D8 si és per modalitat) |
| `signup.text.imageConsent` | localizedText | «Autoritzo la publicació de fotos meves i del meu gos dins l'àmbit de les activitats del club» | aclariment emergent |
| `signup.text.closed` | localizedText | `{"ca": "Les inscripcions estan tancades temporalment. Torna-ho a provar més endavant o posa't en contacte amb el club.", "es": "Las inscripciones están cerradas temporalmente. Vuelve a intentarlo más adelante o ponte en contacto con el club.", "en": "Registration is temporarily closed. Please try again later or contact the club."}` | |
| `club.privacyPolicyUrl` | (CLUB.legal) | agilitycanic.cat/ca/politica-de-privacidad/ | «Pots consultar-la aquí» — viu a CLUB, es mostra aquí |
| `signup.onboardingFields` | json `[{key, required}]` | `[{"key": "name", "required": true}, {"key": "locale", "required": true}, {"key": "phone", "required": false}]` | pantalla «Completa el teu perfil» (S01 §14): camps demanats al primer accés dels comptes importats/migrats; claus admeses `name`, `locale`, `phone` (afegit 06-09, organitzador) |
| `legal.maxPostpones` | int | 3 | A12: vegades que es pot posposar l'acceptació d'una versió nova de la política (pop-up «Més tard»); a 0 el front bloqueja (afegit 06-09, organitzador) |

## Classes i reserves (`bookings.*`, `classes.*`) — bloc Classes
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `bookings.maxCurrentWeek` | int | 2 | PAR-02 (per unitat de `bookings.limitUnit`) |
| `bookings.maxNextWeek` | int | 1 | PAR-03 |
| `bookings.limitUnit` | enum `DOG`/`MEMBER` | DOG | ADR-012 |
| `bookings.weekOpensAt` | json `{dayOfWeek, time}` | `{SUNDAY, 20:00}` | PAR-04; obre la setmana vinent **i** reinicia el comptador d'entrenaments (Q-03); hora local del club |
| `bookings.lateCancelThresholdMinutes` | duration | 240 | «llindar d'anul·lació tardana 4 h» (PAR-05; **Jordi 05-09: 4 h, editable a D11** — tanca el (*) del Josep a 06) |
| `bookings.seatHoldSeconds` | int | 30 | BLOQUEIG_TEMPORAL a 06/29 |
| `bookings.showInstructorHoursBefore` | int | 24 | PAR-24 «el dia abans»; `0` = sempre |
| `bookings.instructorLastMinuteNotice` | bool | true | l'instructor pot marcar «ha avisat» en nom de l'alumne |
| `classes.defaultCapacity` | int | 5 | PAR-21; l'aforament real = min(aforament dels nivells) |
| `classes.minDogs` | int | 2 | BR-15 |
| `classes.riskReviewTime` | time | 07:30 | PAR-27 actualitzat |
| `classes.riskLookaheadDays` | int | 2 | «avui + 2 dies vista» |
| `classes.riskAutoCancelSameDay` | bool | true | el dia mateix: anul·lació; dies següents: només avís |
| `classes.maxInstructorsPerClass` | int | 1 | ADR-012 |
| `classes.slotMinutes` | int | 10 | granularitat de la graella de plantilles (franges lliures) |
| `levels.enabled` | bool | true | ADR-012 |
| `coverage.thresholds` | json `{ok, tight, short}` % | `{240, 190, 150}` | bé > ok · ajustat ok–tight · manca oferta tight–short · cal ampliar < short |
| `coverage.activeDogWeeks` | int | 2 | «actius» = amb reserva la setmana en curs o l'anterior |

## Llista d'espera (`waitlist.*`) — bloc Llista d'espera
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `waitlist.mode` | enum `ALL_AT_ONCE`/`FIFO` | ALL_AT_ONCE | ADR-012 |
| `waitlist.fifoConfirmMinutes` | int | 30 | només FIFO |
| `waitlist.maxPerClass` | int | 3 | PAR-23 |
| `waitlist.maxPerDogPerWeek` | int | 2 | PAR-22 |
| `waitlist.maxPerDogPerWeekIfAttended` | int | 1 | «1 si ja ha fet classe» |
| `waitlist.notifyThresholdMinutes` | duration | 30 | «llindar d'avís a la llista d'espera» (R28-08): per sota, la plaça s'allibera sense avís |

## Entrenaments lliures (`training.*`) — bloc Entrenaments
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `training.slotMinutes` | int | 30 | PAR-10 |
| `training.bookingWindowDays` | int | 3 | «dia en curs + 3 dies naturals» |
| `training.maxPerWeek` | int | 3 | PAR-07; el comptador es reinicia a `bookings.weekOpensAt` |
| `training.cancelThresholdMinutes` | duration | 120 | |
| `training.capacityPerRingSlot` | int (àmbit `ring`) | 1 | PAR-12; override a `Ring.trainingCapacity` |
| `training.freeTrainingRequiresLicense` | bool | false | el Cànic ho diu a les condicions, no ho valida el sistema (decisió: informatiu) |

## Club i pistes (`club.*`) — bloc Club i pistes
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `club.openingHours` | json per dia `{open, close}` | dl–dg 07:00–22:00 | PAR-09; genera els slots d'entrenament |
| `club.holidays` | list de dates | — | PAR-11: no es generen classes ni slots |
| `club.timeZone`, `club.locales`, `club.defaultLocale`, `club.currency`, `club.countryProfile` | (CLUB) | Europe/Madrid · ca,es · ca · EUR · ES | ADR-011 — es mostren aquí, viuen a CLUB |

## Quotes i cobraments (`billing.*`) — bloc Quotes i remesa
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `billing.entryFeePerDog` | money | 100,00 € | PAR-29; override per modalitat (`Plan.entryFee`) |
| `billing.nextInvoiceDayOfMonth` | int | 1 | la remesa avança el proper rebut al dia 1 del mes següent |
| `billing.familyDiscountPercentFromSecondDog` | int | 50 | «2 gossos = 90 €» = tarifa familiar del catàleg; el % s'usa per proposar la tarifa a la validació |
| `billing.cashPeriodMonths` | int | 6 | efectiu per semestres naturals |
| `billing.inactivityFeeFirstMonth` | money | 20,00 € | PAR-28 |
| `billing.inactivityFeeFollowingMonths` | money | 10,00 € | PAR-28 |
| `billing.packLowBalanceSessions` | int | 1 | N-11 |
| `billing.packExpiryWarningDays` | int | 14 | N-11 |
| `billing.singleClassCancelPolicy` | enum `REFUND`/`CREDIT`/`NONE` | REFUND | PAY_TO_BOOK, dins termini |
| `billing.stripeReceiptEmail` | bool | true | Stripe envia rebut per correu |
| `billing.accountingExportFormat` | enum `CSV`/`XLSX` | CSV | pendent §2 de PENDENTS |
| `billing.sepa.creditor*`, `billing.sepa.invoiceSeries` | (CLUB.paymentProviders) | — | es mostren aquí, viuen a CLUB |

## Inactivitat i baixa (`inactivity.*`, `leave.*`) — bloc Quotes i remesa
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `inactivity.requestDeadlineDay` | int | 25 | sol·licitud i canvis fins al dia 25 del mes anterior |
| `inactivity.cancelBookingsOnApproval` | bool | true | BR-16 |
| `leave.reasons` | json `[{key, label: localizedText, audience}]` | `[{"key": "LEARNED_ENOUGH", "label": {"ca": "He après el que volia", "es": "He aprendido lo que quería", "en": "I have learned what I wanted"}, "audience": "MEMBER"}, {"key": "NO_TIME", "label": {"ca": "No trobo temps", "es": "No encuentro tiempo", "en": "I cannot find the time"}, "audience": "MEMBER"}, {"key": "NOT_EXPECTED", "label": {"ca": "No és el que esperava", "es": "No es lo que esperaba", "en": "It is not what I expected"}, "audience": "MEMBER"}, {"key": "EXTERNAL", "label": {"ca": "Condicionants aliens", "es": "Circunstancias ajenas", "en": "External circumstances"}, "audience": "MEMBER"}, {"key": "OTHER", "label": {"ca": "Altres", "es": "Otros", "en": "Other"}, "audience": "MEMBER"}, {"key": "CLUB_DECISION", "label": {"ca": "Decisió del club", "es": "Decisión del club", "en": "Club decision"}, "audience": "ADMIN"}, {"key": "PACK_EXPIRED", "label": {"ca": "Pack caducat", "es": "Pack caducado", "en": "Expired pack"}, "audience": "SYSTEM"}]` | pantalla 15 |
| `leave.npsEnabled` | bool | true | |
| `leave.fullMonthIfLater` | bool | true | mes posterior = mes sencer cobrat |

## Comunicacions (`messaging.*`) — bloc Comunicacions
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `messaging.noShowNoticeTime` | time | 08:00 | lot de l'endemà |
| `messaging.reminderOptionsMinutes` | list | 60,120,240,360,720,1440 | opcions de 12; «mai» sempre disponible |
| `messaging.email.fromName` | string | Club Agility Cànic | remitent per club (domini verificat al proveïdor) |
| `messaging.email.fromAddress` | string | `"no-reply@agilitydoghub.com"` | domini verificat al proveïdor (E1-T03) |
| `messaging.email.replyTo` | string | `""` | buit = correu de contacte del club en enviar |
| `messaging.sms.senderId` | string | `"AgilityHub"` | Twilio (pendent §3) |
| `messaging.sms.monthlyCap` | int | 1000 | límit de seguretat; en arribar-hi, avís a l'admin i el canal cau a correu |
| `messaging.notifyWeekOpening` | bool | false | «Ja pots reservar la setmana vinent» (N-33) |
| `messaging.notifyNewRingSetup` | bool | false | N-31 |
| `messaging.documentReminderDays` | int | 0 | 0 = recordatori de cartilla pendent només manual |

## Fitxers (`files.*`) — bloc sistema
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `files.maxSizeMb` | int | 25 | adjunts (v1.6: «mida màxima per fitxer: paràmetre») |
| `files.allowedTypes` | list | image/*, video/mp4, video/quicktime, application/pdf | |
| `files.dogPhotoMaxMb` | int | 8 | |

## Històric i tauler (`history.*`, `dashboard.*`)
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `history.monthsVisible` | int | 2 | pantalla 25 |
| `dashboard.pendingSignupAgeWarnDays` | int | 7 | D1: antiguitat destacada |

## Recorreguts (`courses.*`) — bloc Recorreguts
| Clau | Tipus | Cànic | Notes |
|---|---|---|---|
| `courses.setupAutoExpireDays` | int | 7 | caducitat automàtica del «recorregut muntat» |
| `courses.placementMarginMeters` | json decimal | 1.5 | marge mínim entre obstacle i límit del ring (avís) |
| `courses.showSetupToMembers` | bool | true | l'alumne veu el recorregut muntat a 08/10 |
| `courses.allowInstructorPublish` | bool | true | l'instructor pot registrar muntatges sense l'admin |

## Regles del catàleg

1. Afegir un paràmetre = afegir-lo aquí **i** a `ParameterCatalog` (clau, tipus, default, bloc D11, validació) **i** a la pantalla D11 (es genera del catàleg: cap formulari fet a mà per paràmetre) **i** un test de la branca.
2. Els valors de `time` i `dayOfWeek` són en **hora local del club** (`CLUB.timeZone`).
3. Els `money` porten la moneda del club.
4. Els `localizedText` es mantenen des de D11 amb un editor per idioma actiu del club.
5. Canvi de paràmetre → `ParameterChanged` (auditat, amb valor anterior/nou) i invalidació de la cache de configuració del club (TTL 60 s).

## Annex A — claus acceptades el 03-09 a partir de les propostes de les specs (§13)

Totes entren a `ParameterCatalog` amb el mateix criteri (default de producte = valor del Cànic). Bloc «sistema» = no visible a D11.

| Clau | Tipus | Defecte | Bloc D11 | Spec |
|---|---|---|---|---|
| `auth.welcomeLinkDays` | int | 7 | sistema | S01/S04 |
| `auth.maxSessions` | int | 10 | sistema | S01 |
| `auth.lockoutMinutes` · `auth.lockoutMaxAttempts` | int | 15 · 5 | sistema | S01 |
| `auth.checkCompromisedPasswords` | bool | true | sistema | S01 |
| `census.dogDocumentTypes` | json `[{key, label: localizedText, required}]` | `[{"key": "VACCINATION_CARD", "label": {"ca": "Cartilla de vacunes", "es": "Cartilla de vacunas", "en": "Vaccination card"}, "required": true}, {"key": "INSURANCE", "label": {"ca": "Assegurança", "es": "Seguro", "en": "Insurance"}, "required": false}, {"key": "OTHER", "label": {"ca": "Altres", "es": "Otros", "en": "Other"}, "required": false}]` | Alta i consentiments | S03 |
| `census.bookingBlockReasons` | list localizedText | rebut impagat · cartilla pendent · decisió del club | Club i pistes | S03 |
| `signup.text.monthlyPaymentIntro` | localizedText | `{"ca": "La quota mensual per abonats es cobrarà normalment l'últim dia de cada mes. En el cas de packs no es genera cap càrrec mensual.", "es": "La cuota mensual para abonados se cobrará normalmente el último día de cada mes. En el caso de packs no se genera ningún cargo mensual.", "en": "The monthly membership fee is normally collected on the last day of each month. Packs do not generate a monthly charge."}` | Alta i consentiments | S04 |
| `signup.text.therapyIntro` | localizedText | `{"ca": "Es poden fer també classes de teràpia individual, combinades amb les classes en grup o com a pas previ. Si la teràpia es combina amb classes en grup, selecciona l'opció d'Abonat o Pack; si d'entrada no faràs classes en grup, selecciona l'opció Teràpia.", "es": "También se pueden hacer clases de terapia individual, combinadas con las clases en grupo o como paso previo. Si la terapia se combina con clases en grupo, selecciona la opción de Abonado o Pack; si inicialmente no harás clases en grupo, selecciona la opción Terapia.", "en": "Individual therapy classes are also available, alongside group classes or as a preliminary step. If therapy is combined with group classes, select Membership or Pack; if you will not initially attend group classes, select Therapy."}` | Alta i consentiments | S04 |
| `signup.text.familyGroupIntro` | localizedText | `{"ca": "Si a casa ja hi ha algú abonat, podeu formar un grup familiar amb un únic responsable del pagament: la quota mensual es redueix a partir del segon gos. Així, per dos gossos la quota mensual seria de {twoDogsMonthlyFee}. L'entrada sí que és per cada gos.", "es": "Si en casa ya hay alguien abonado, podéis formar un grupo familiar con un único responsable del pago: la cuota mensual se reduce a partir del segundo perro. Así, para dos perros la cuota mensual sería de {twoDogsMonthlyFee}. La matrícula sí es por cada perro.", "en": "If someone in your household is already a member, you can form a family group with one person responsible for payment: the monthly fee is reduced from the second dog. For two dogs, the monthly fee would be {twoDogsMonthlyFee}. The joining fee still applies to each dog."}` | Alta i consentiments | S04 |
| `signup.rateLimit` | json | `{"identityChecksPerHour": 10, "familyGroupLookupsPerHour": 20, "uploadUrlsPerHour": 30, "signupPerHour": 5, "signupPerDay": 20, "checkoutSessionsAnonymousPerHour": 10, "townsPerHour": 60}` | sistema | S04 |
| `activities.cancelDeadline` | enum `REGISTRATION_CLOSE · EVENT_START` | REGISTRATION_CLOSE | Classes | S07 |
| `activities.publicUrlTemplate` | string | `{websiteUrl}/activitat/{slug}` | Club i pistes | S07 |
| `bookings.paymentPendingMinutes` | int | 30 | Classes | S08 |
| `ringBlocks.maxHorizonDays` | int | 60 | Entrenaments | S09 |
| `attendance.editDays` | int | 1 | Classes | S10 |
| `files.maxAttachmentsPerEntity` | int | 10 | sistema | S10 |
| `messaging.sms.transliterateToGsm7` | bool | true | Comunicacions | S11 |
| `messaging.push.ttlMinutes` | int | 1440 | sistema | S11 |
| `billing.invoiceSeriesPattern` · `billing.invoiceResetYearly` | string · bool | `{YYYY}` · true | Quotes i remesa | S12 |
| `billing.cashInvoicing` | enum `MONTHLY · SEMESTER` | MONTHLY (confirmat Jordi 05-09) | Quotes i remesa | S12 |
| `billing.taxIncluded` | bool | true | Quotes i remesa | S12 |
| `billing.sepa.useFrst` · `billing.sepa.schema` · `billing.sepa.collectionDayOfMonth` | bool · enum · int (1–28 o `0` = últim dia del mes) | false · `pain.008.001.02` · **0** (últim dia del mes, Jordi 05-09) | Quotes i remesa | S12 |
| `billing.stripeMaxAttempts` | int | 3 | Quotes i remesa | S12 |
| `billing.remittanceReminderDay` | int (0 = mai) | 22 | Quotes i remesa | S12/S15 |
| `inactivity.maxStartMonthsAhead` | int | 12 | Quotes i remesa | S13 |
| `leave.packExpiryGraceDays` | int | 30 | Quotes i remesa | S13 |
| `leave.reasons` (canvi de tipus) | json `[{key, label: localizedText, audience}]` | `[{"key": "LEARNED_ENOUGH", "label": {"ca": "He après el que volia", "es": "He aprendido lo que quería", "en": "I have learned what I wanted"}, "audience": "MEMBER"}, {"key": "NO_TIME", "label": {"ca": "No trobo temps", "es": "No encuentro tiempo", "en": "I cannot find the time"}, "audience": "MEMBER"}, {"key": "NOT_EXPECTED", "label": {"ca": "No és el que esperava", "es": "No es lo que esperaba", "en": "It is not what I expected"}, "audience": "MEMBER"}, {"key": "EXTERNAL", "label": {"ca": "Condicionants aliens", "es": "Circunstancias ajenas", "en": "External circumstances"}, "audience": "MEMBER"}, {"key": "OTHER", "label": {"ca": "Altres", "es": "Otros", "en": "Other"}, "audience": "MEMBER"}, {"key": "CLUB_DECISION", "label": {"ca": "Decisió del club", "es": "Decisión del club", "en": "Club decision"}, "audience": "ADMIN"}, {"key": "PACK_EXPIRED", "label": {"ca": "Pack caducat", "es": "Pack caducado", "en": "Expired pack"}, "audience": "SYSTEM"}]` | Quotes i remesa | S13 |
| `audit.retentionYears` | int | 6 | Privacitat i auditoria | S14 |
| `rgpd.erasureMinDaysAfterLeave` · `rgpd.retentionYearsAfterLeave` · `rgpd.rejectedSignupRetentionDays` | int | 30 · 5 · 365 | Privacitat i auditoria | S14 |
| `security.eventRetentionDays` | int | 90 | sistema | S14 |
| `jobs.<nom>.enabled` (weekOpening · riskReview · noShowNotices · reminders · expirations · waitlistFifo · paymentTimeouts · classFinishing · cleanup · billingReminder) | bool | true | Processos automàtics | S15 |
| `jobs.dailyTime` | time | 06:00 | Processos automàtics | S15 |
| `jobs.alertAdminsOnFailure` | bool | true | Processos automàtics | S15 |
| `jobs.retention.domainEventsDays` · `jobRunsDays` · `stripeEventsDays` · `exportFilesDays` · `orphanUploadsHours` | int | 90 · 90 · 400 · 7 · 48 | sistema | S15 |
| `classes.finishGraceMinutes` | int | 15 | Classes | S15 |
| `courses.buildSessionMaxHours` · `courses.gateClearanceMeters` · `courses.obstacleClearanceMeters` | int · decimal · decimal | 12 · 1.5 · 0.5 | Recorreguts | S16 |
| `files.allowedTypes` (+ `text/plain` per a Smarter) | list | — | sistema | S16 |
| `platform.domainRecheckDays` · `platform.supportAccessMinutes` | int | 7 · 60 | sistema (consola) | S17 |
| `migration.exportRetentionDays` · `migration.leftMaxYears` · `migration.reconciliationTolerancePct` · `migration.playoffReadOnlyMonths` | int | 30 · 5 · 1 · 3 | sistema | S18 |
| `learn.linkText` | localizedText | «Aprèn amb AgilityHub» | Club i pistes | S19 |
| `learn.recommendationsTtlMinutes` | int | 60 | sistema | S19 |
| `learn.baseUrl` | string | `"https://learn.agilitydoghub.com"` | sistema | S19 |

Bloc nou a D11: **«Privacitat i auditoria»** (S14) i **«Processos automàtics»** (S15).
