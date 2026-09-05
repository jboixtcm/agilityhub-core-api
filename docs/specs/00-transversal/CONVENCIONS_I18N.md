# Convencions d'internacionalització (ADR-011)

**v1.0 · 03-09-2026** · Idiomes de producte a R1: **ca, es, en** (complets). Afegir-ne un = afegir `locales/<xx>/*.json` al front i `messages_<xx>.properties` al back; cap canvi de codi.

## 1. Front (`packages/i18n`)

- `react-i18next` + `i18next-icu`. Namespaces per mòdul: `common`, `auth`, `home`, `booking`, `training`, `dogs`, `profile`, `signup`, `instructor`, `history`, `notifications`, `faq`, `admin-census`, `admin-scheduling`, `admin-billing`, `admin-messaging`, `admin-settings`, `courses`, `console`, `errors`, `enums`.
- Claus: `namespace:pantalla.element[.variant]` en anglès i camelCase — `booking:confirm.holdCountdown`, `admin-billing:remittance.rollback.confirmTitle`. Els enums es tradueixen a `enums:` per valor (`enums:bookingState.CANCELLED_LATE`).
- **Mai** text literal a JSX, **mai** concatenació (`t('x') + ' ' + name`): interpolació ICU amb variables tipades.
- ICU: plurals `{count, plural, =0 {cap plaça} one {# plaça} other {# places}}` · gènere `{gender, select, female {Benvinguda} other {Benvingut}}` · dates/números **no** dins l'ICU: es formaten abans amb `Intl.DateTimeFormat(locale, {timeZone})` / `Intl.NumberFormat(locale, {style:'currency', currency})` i es passen com a string.
- Fus horari: tota data que mostra l'app es formata amb el `timeZone` del club (de `/branding`), no amb el del dispositiu; la data «d'avui» dels quadres és la del club.
- Formats compartits (`packages/i18n/format.ts`): `fmtDate(short|long|weekday)`, `fmtTime`, `fmtDateTime`, `fmtMoney`, `fmtRelative`, `fmtMonth`.
- Detecció: `Account.locale` > `localStorage` (anònims) > `navigator.language` ∩ `club.locales` > `club.defaultLocale`.
- Selector d'idioma: a 12 (Perfil), a `apps/id` i al peu de l'alta pública. Canviar-lo escriu `Account.locale` (`PATCH /me`).
- CI: `i18next-parser` extreu claus; falla si una clau no existeix en algun dels idiomes de producte; test de vocabulari prohibit (`parell`, `parella`, `parelles`, `amigable`, `(paràmetre)`, `F8`, `RF-`, `BR-`, `PAR-`) sobre `ca` i `es`.
- Els textos del **Cànic als mockups** són la traducció `ca` de la clau o el valor `ca` d'un `LocalizedText`/paràmetre; en cas de dubte, el mockup mana per a `ca` i la traducció `es`/`en` la fa la IA amb revisió.

## 2. Back (`agilityhub-core-api`)

- `MessageSource` amb ICU4J (`com.ibm.icu.text.MessageFormat`), fitxers `messages_ca.properties`, `messages_es.properties`, `messages_en.properties` per context; claus `notif.N-08a.title`, `notif.N-08a.body`, `notif.N-08a.sms`, `email.magicLink.subject`, `error.BOOKING_LIMIT_REACHED`…
- Locale de la petició: `Account.locale` del JWT; anònims: `Accept-Language` ∩ `club.locales`; processos programats: el locale de cada destinatari.
- Correus: plantilles Thymeleaf per idioma amb layout comú (logo i colors del club); assumpte i cos de `messages_*`; SMS de `*.sms` (curt, GSM-7).
- `LocalizedText` a l'API: lectura resolta + mapa (`nameI18n`) quan és editable; escriptura del mapa; validació: almenys el `defaultLocale` del club.
- Errors: `ErrorCode` enum → missatge localitzat; el front tradueix per `code` (namespace `errors`) i usa el `message` com a fallback.
- Dates: instants UTC; conversió a hora local del club **només** per a (a) càlculs de negoci que depenen del dia/hora local (llindars, obertura, schedulers) amb `ZoneId` del club i (b) textos de notificació.

## 3. Contingut del club (D8, D9, D11, D7, FAQ)

- Editor per idioma actiu del club (pestanyes `ca | es | …`) per a cada camp `LocalizedText`; el `defaultLocale` és obligatori, la resta opcional (fallback).
- Plantilles de comunicat: títol, cos, `smsBody` per idioma + vista prèvia amb dades fictícies en cada idioma.

## 4. Perfil de país (S02)

Les validacions locals (document d'identitat, CP, telèfon, IBAN/mandat, NIF) no viuen a les pantalles: el front demana `GET /branding` → `countryProfile: {idDocumentTypes[], postalCodeLookup: bool, phonePrefix, requiresIban, mandateTextKey}` i renderitza els camps en conseqüència; el back valida amb la implementació del perfil.

## 5. Proves obligatòries

- Snapshot de cada plantilla de correu/SMS en els tres idiomes amb dades fictícies.
- Test de fus horari: club a `Europe/Madrid` i club a `America/Argentina/Buenos_Aires` amb la mateixa hora UTC → llindar de 2 h i obertura de diumenge 20:00 correctes a cadascun.
- Test de `LocalizedText` fallback (usuari `en`, club `ca`/`es`).
