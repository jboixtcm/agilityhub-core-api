# ADR-011 — Internacionalització i localització (idioma, fus horari, moneda, perfil de país)

**Estat:** Acceptada
**Data:** 2026-09-03
**Decisors:** Jordi (amb assistència IA)

## Context

El producte arrenca en català i castellà però **«no ha de costar res anar a qualsevol altre idioma»** (Jordi 03-09) i AgilityHub és internacional. El pla v1 només preveia claus i18n a la UI. Un club fora d'Espanya trencaria: els processos programats («7:30», «diumenge 20:00» — hora local del club), la moneda, el DNI/NIE, el CP→població, el prefix +34, la remesa SEPA, Bizum, el text del «dia 25» i el mandat SEPA en castellà.

## Opcions considerades

1. **Idioma total + perfil de país** — UI, correus, SMS i notificacions en l'idioma de l'usuari; club amb fus horari, moneda, idiomes i un perfil de país que encapsula les regles locals; només Espanya implementada, darrere d'interfície.
2. Només idioma de la UI, regles espanyoles al codi — ràpid ara, refactor car al primer club estranger.
3. Multi-país complet a l'MVP — diversos perfils i proveïdors des del primer dia; no cal per a R1.

## Decisió

**Opció 1**, amb aquestes regles:

### Idioma
- **Idioma de l'usuari** = `ACCOUNT.locale` (BCP-47: `ca`, `es`, `en`…). Anònims: `Accept-Language` filtrat pels idiomes del club. **Idiomes del club** = `CLUB.locales` (llista) + `CLUB.defaultLocale`.
- **Idiomes de producte** (05-09, verificat a Learn: `de en es fr no pt`) = unió `ca es en fr de no pt`; `Account.locale` admet qualsevol dels set; cada app serveix els que té (Clubs: `ca/es/en` a R1, fallback a `club.defaultLocale`; Learn: els seus sis, fallback `es`).
- **Fitxers de traducció complets per a `ca`, `es` i `en` des de R1** (amb IA el cost de mantenir tres idiomes és baix; `en` cobreix ID, Learn i clubs futurs). El selector d'idioma s'activa a l'app des del primer dia (la regla «amagar-lo fins que ES estigui complet» del model queda superada).
- **Front**: `react-i18next` + `i18next-icu` (plurals, gènere, select) · namespaces per mòdul a `packages/i18n/locales/{locale}/{modul}.json` · claus `modul.pantalla.element` · **mai text literal al codi ni concatenació de frases** · formats de data, hora, número i moneda amb `Intl` segons `locale` de l'usuari i `timeZone` del club · linter `i18next-parser` a CI: clau que falta en algun idioma = build vermell · linter de vocabulari prohibit (`parell`, `amigable`, codis F8/RF…) sobre els JSON.
- **Back**: `MessageSource` + ICU4J per a correus, SMS, push, títols/cossos de notificació i errors de validació (`code` + missatge localitzat; el front tradueix per `code` i usa el missatge del back com a fallback). Plantilles de correu per idioma amb layout comú.
- **Contingut del club** (catàlegs de text lliure): tipus `LocalizedText = {locale: string}` amb **fallback al `defaultLocale` del club**. S'aplica a: MODALITAT (nom, textos de presentació), PLANTILLA_COMUNICAT (títol, cos), FAQ, ACTIVITAT (títol, descripcions), NIVELL.nom (opcional), textos de la pantalla d'alta (condicions, «dia 25»…) que passen a ser **paràmetres de tipus text localitzat**. PISTA.nom i noms propis no es tradueixen.
- **Gènere**: ICU `select` amb `gender` de l'abonat (`male|female|other` → `other` cau a masculí en català/castellà, com decideix el model).

### Fus horari i temps
- Tots els instants es guarden en **UTC**; `CLUB.timeZone` (IANA, `Europe/Madrid`). Dates «de negoci» (dia de classe, mes de rebut) es guarden com a `LocalDate`/`YearMonth`.
- **Processos programats**: un *tick* per minut recorre els clubs i executa el que toca segons l'hora local de cada club (7:30, 8:00, diumenge 20:00 són paràmetres en hora local). Tests amb `Clock` injectable i clubs en dos fusos.
- Primer dia de la setmana i format de dates segons `locale`; la «setmana» de negoci (obertura d'inscripcions) es defineix per paràmetre (`bookings.weekOpensAt` = dia + hora local).

### Moneda i imports
- `CLUB.currency` (ISO 4217); tots els imports com a `Money {amountMinor: long, currency}`; mai `double`. Els rebuts congelen import i moneda.
- IVA/impostos: percentatge per tarifa (ja al model) — el nom de l'impost és paràmetre del perfil de país.

### Perfil de país (`CLUB.countryProfile`)
Interfície `CountryProfile` amb implementació **`ES`** a R1 i **`GENERIC`** com a fallback:

| Regla | ES | GENERIC |
|---|---|---|
| Document d'identitat de la persona | DNI/NIE validats formalment; passaport si no n'hi ha | camp lliure «document d'identitat» + tipus |
| Codi postal → població | dataset de CPs d'Espanya (proposta amb llista si n'hi ha més d'una) | camp lliure |
| Telèfon | prefix per defecte +34, 9 dígits | E.164 amb prefix obligatori |
| IBAN | validació IBAN (genèrica) + text del mandat SEPA en l'idioma de l'usuari (llei 16/2009 només si el país és ES) | validació IBAN genèrica; mandat SEPA només si el club activa `SEPA_XML` |
| NIF del club / titular | format espanyol | lliure |
| Mètodes de pagament oferts a l'alta | segons `CLUB.paymentProviders` (ADR-009) | idem |
| Textos legals | URL de política de privacitat del club (paràmetre) | idem |

Afegir un país = una classe nova + el seu dataset + tests; cap canvi a les pantalles.

## Conseqüències

- E0 inclou la infraestructura i18n als dos repos i el perfil de país al core; cada vertical lliura les seves claus en **tres idiomes** dins del mateix PR (DoD).
- El model incorpora `LocalizedText` i els camps de CLUB (`locales`, `defaultLocale`, `timeZone`, `currency`, `countryProfile`) — `MODEL_DADES_PLATAFORMA.md`.
- Els mockups mostren textos del Cànic en català: són el valor del paràmetre/`LocalizedText` en `ca`, no text de codi.
- Cost afegit acceptat: disciplina de claus i tests de fus horari; a canvi, un club estranger és configuració + un perfil.
