# Mapatge de camps de l'export de Playoff → model AgilityHub

**v1.0 · 09-09-2026** · Font: tres exports del **07-09-2026** que Jordi ha passat (`Socis` complet, `Associats / Tipologia`, `Associats / Nivell`). **Els fitxers no viuen en aquesta carpeta** (Dropbox + dades personals reals): s'han llegit fora i aquí només hi ha **noms de columna, recomptes i valors de categoria**. Aquest document tanca la pregunta B7 del registre i és la referència de l'importador (S18) i del generador de fixtures anonimitzades (R-18-02).

## 1. Què hi ha als exports

| Export | Files | Contingut |
|---|---|---|
| `Socis` | **1.436** fitxes (`184` Alta · `1.252` Baixa) × **51** columnes | la fitxa de Playoff = **persona + un gos** |
| `Associats / Tipologia` | 182 files, 182 associats (1 tipologia per associat) | la modalitat de cada abonat d'alta |
| `Associats / Nivell` | 240 files, 182 associats | nivell del gos **+ marques** (llicència, teràpies) com a files addicionals |

Dels 184 d'alta, **182 tenen nivell i tipologia** i 2 no en tenen cap. De les 1.252 baixes, **880 són del 2021 ençà** (les que es migren segons A24a: baixes de fa més de 5 anys no es migren) i 372 són anteriors o sense data.

Els tres exports es creuen per **`ID associat`**.

## 2. Taula A — `Socis` (51 columnes)

Ompliment = files informades **sobre les 184 altes** (entre parèntesis, sobre les 1.436 totals quan és rellevant).

| # | Columna Playoff | Ompliment | Destí al model AgilityHub | Nota |
|---|---|---|---|---|
| 1 | ID associat | 184 | `Member.sourceIds.playoffMemberId` · `Dog.sourceIds.playoffMemberId` | clau de creuament amb Tipologia i Nivell; idempotència de la càrrega |
| 2 | ID subcategoria | 181 | — | id intern de la tipologia (22 valors); no aporta res |
| 3 | Foto | 145 | `Dog.photoFileKey` (proposta) | URL a l'S3 de Playoff; es descarrega al tall i es torna a pujar. **Dubte B29**: la foto és del gos o de la persona? |
| 4 | Núm. assoc. | 184 | `Member.memberNumber` | 4 números repetits a tot l'export → `NUMBER_CONFLICT` (R-18-05) |
| 5 | Estat | 184 | `Member.status` | `Alta → ACTIVE` · `Baixa → LEFT`; en aquest export només hi ha aquests dos valors |
| 6 | Nom | 184 | `Member.firstName` | |
| 7 | Cognoms | 184 | `Member.lastName1/lastName2` + **`Dog.name`** | **173 de 184** porten el nom del gos entre parèntesis: «Cognom Cognom (Duna)» → R-18-04(c) |
| 8 | NIF | 184 | `Member.idDocument {type: DNI\|NIE, number}` | normalitzat; tipus deduït del format (perfil ES) |
| 9 | Passaport | 9 | `Member.idDocument {type: PASSPORT, number}` | s'usa quan la 10 és `S` |
| 10 | Té passaport? | 184 | — | selector de la 8 vs la 9 |
| 11 | Data naixement | 184 | `Member.birthDate` | ⚠️ **hi ha dues columnes amb el mateix títol** (l'11 és de la persona, la 41 del gos): l'importador llegeix **per posició**, no per nom |
| 12 | Edat | 184 | — | derivada |
| 13 | Gènere | 184 | `Member.gender` | `Femení → FEMALE` · `Masculí → MALE` · `Altres / No binari → OTHER` |
| 14 | Estat civil | 7 | — | dada residual del programa antic |
| 15 | Telèfon principal | 174 | `Member.phones[0] {prefix, number}` | normalització a E.164 amb el prefix del perfil de país |
| 16 | Telèfon secundari | 110 | `Member.phones[1]` | el model n'admet **2** |
| 17 | Codi postal | 176 | `Member.address.postalCode` | |
| 18 | Domicili | 174 | `Member.address.street` | |
| 19 | Municipi | ~180 | `Member.address.city` | |
| 20 | Província | ~180 | `Member.address.province` | 4 valors amb variants de majúscules → es normalitza |
| 21 | País | 184 | `Member.address.country` | `Espanya → ES`; 2 casos no ES a tot l'export |
| 22 | Nacionalitat | 184 | — | no la fem servir enlloc |
| 23 | Email principal | 177 | `Member.contactEmails[0]` + email de l'`Account` | **7 altes sense email** → sense compte fins que el club el demani (R-18-12) |
| 24 | Email secundari | 77 | `Member.contactEmails[1]` | el model n'admet **2** |
| 25 | Web | 1 | — | soroll (una adreça de correu) |
| 26 | Data alta | 184 | `Member.joinedAt` | |
| 27 | Data baixa | (1.245) | `Member.leaveDate` + `leftAt` + `leftReason = MIGRATED` | |
| 28 | IBAN | 158 | `Member.paymentMethod.SEPA_DD.iban` (xifrat) | **26 altes amb domiciliació i sense IBAN** → incidència `NO_BANK_ACCOUNT` («Compte no informat») |
| 29 | Titular banc | 118 | `paymentMethod.SEPA_DD.holderName` | buit → nom de l'abonat |
| 30 | App+Notificacions | 184 | `Member.notificationPreferences` | `No` (una tercera part) → només avisos essencials; `Sí` → per defecte del club |
| 31 | Observacions | 9 | `Member.internalNotes` | notes internes (ADMIN) |
| 32–36 | Impagats · Import impagats · Data impagat · Pendents · Import pendents | **0 / 0 €** | — | confirma la resposta B8 del Josep: **no hi ha cap impagat** |
| 37 | Mètode pagament | 184 | `Member.paymentMethod.type` | `Domiciliació bancària → SEPA_DD` (177) · `Transferència → MANUAL{TRANSFER}` (1) · `Paga en efectiu → MANUAL{CASH}` (6) |
| 38 | Adjunts | 0 | — | |
| 39 | Nom del gos | 175 | `Dog.name` | 9 altes sense gos → `DOG_INFERRED` (R-18-04b) |
| 40 | Sexe del gos | 175 | `Dog.sex` | `Femella → FEMALE` · `Mascle → MALE` |
| 41 | Data naixement (2a) | 175 | `Dog.birthDate` | vegeu l'avís de la fila 11 |
| 42 | Raça del gos | 173 | `Dog.breed` | 473 valors diferents a tot l'export: text lliure, no catàleg |
| 43 | Numero de xip | 159 | `Dog.chip` | **16 gossos sense xip**; el model el fa obligatori → es carrega buit amb avís `CHIP_MISSING` i D15 el filtra |
| 44 | **Nom del guia** | 7 (19) | **`Dog.handlerName` — CAMP NOU** (Jordi 09-09) | qui condueix el gos quan no és l'abonat; informatiu, no crea compte ni permisos |
| 45 | Objectius | 42 | `Dog.instructorNote {text}` | és el text que l'abonat escriu per als instructors (mateix camp que «Notes als instructors» de l'alta) |
| 46 | Llicencia RSCE | 40 | `Dog.licenses[] {organisation: "RSCE", number}` | |
| 47 | Llicencia FCAG | 53 | `Dog.licenses[] {organisation: "FCAG", number}` | |
| 48 | Categoria RSCE | 39 | `Dog.licenses[].category` — **CAMP NOU** | mida: `XS · S · M · I · L` |
| 49 | Grau | 37 | `Dog.licenses[].grade` | `1 · 2 · 3` (un valor ve amb espai al davant: es normalitza) |
| 50 | Divisió | 39 | `Dog.licenses[].division` — **CAMP NOU** | `Iniciació · 1D · 2D · DH` |
| 51 | Te clau | 0 | — | mai s'ha fet servir |

**Resum**: dels 51 camps se n'aprofiten **44**; 7 no es migren (ID subcategoria, Edat, Estat civil, Nacionalitat, Web, Adjunts, Te clau) i cap d'ells té ús al producte nou.

## 3. Taula B — `Associats / Tipologia` → modalitat

Un abonat = una tipologia. Cal **normalitzar** abans de mapejar (hi ha variants de majúscules i espais finals: «Abonat » i «abonat » són la mateixa).

| Tipologia de Playoff | Abonats | Modalitat AgilityHub | Nota |
|---|---|---|---|
| Abonat (+ «abonat») | 72 + 24 = **96** | `ABONAT` | |
| familiar abonat | **37** | `ABONAT_FAMILIAR` | + `FamilyGroup` amb el pagador com a titular |
| Abonat 2 gossos (+ «abonat 2 gossos») | 13 + 1 = **14** | `ABONAT_FAMILIAR` (`dogsIncluded = 2`) | |
| Manteniment | **12** | `TERAPIA` | la modalitat ja porta `billingMode = MAINTENANCE` (Josep 08-09) |
| Pack 10 classes | **7** | `PACK10` | saldo real des de «Control packs» |
| Quota reduïda | **4** | ❓ **cap modalitat al catàleg** | **dubte B30** |
| Instructors | **4** | `INSTRUCTOR_FREE` + rol `INSTRUCTOR` | quadra amb la llista d'accessos (4 instructors) |
| Familiar Abonat/curs | **3** | ❓ variant de `FAMILIAR`? | **dubte B31** |
| Competició 1 gos | **3** | `COMPETICIO_1` | cal crear-la al seed (preu a confirmar) |
| Pack 6 classes | **2** | `PACK6` | |

No apareixen a les dades vives: «Abonat 3 gossos», «Abonat Plus» ni «Quota COVID» → es treuen del mapatge (queden com a `LEGACY_PLAN` per si surten en baixes antigues).

## 4. Taula C — `Associats / Nivell` → nivell del gos i marques

| Subcategoria | Files | Destí |
|---|---|---|
| A · B · C · D · E · F · G | 38 · 21 · 22 · 20 · 12 · 27 · 23 | `Dog.levelId` (catàleg de nivells del club) |
| Cadells | 12 | `Dog.levelId = CADELLS` |
| **Pendent** | 6 | ❓ nivell **nou** `PENDENT` al catàleg (proposta) — **dubte B32** |
| Llicencia | 58 | **no és un nivell**: marca informativa; sempre acompanya D, E, F o G (coherent amb «entrenament lliure a partir de D amb llicència») |
| Terapies | 1 | marca de teràpia → coherent amb la modalitat `TERAPIA` |

`Data assignació` de la fila del nivell → `Dog.levelAssignedAt` i primera fila de `Dog.levelHistory[]`.

## 5. Camps que cal afegir al model

| Camp | On | Motiu |
|---|---|---|
| `Dog.handlerName` (string ≤ 80, opcional) | S03 §3, MODEL_DADES_PLATAFORMA Annex B, fitxa del gos, D15 (columna «Guia»), llistes d'assistència | petició de Jordi (09-09) i columna 44 de l'export |
| `Dog.licenses[].category` i `.division` | S03 §3 (`licenses[]` passa a `{organisation, number, category?, grade?, division?}`) | columnes 48 i 50; sense això es perdrien |

## 6. Incidències que l'importador ha de tractar (mesurades sobre les 184 altes)

| Cas | Quantitat | Tractament |
|---|---|---|
| Domiciliació sense IBAN | **26** | `NO_BANK_ACCOUNT`; l'abonat es migra i queda fora de la primera remesa fins que el club entri l'IBAN (llista a D6) |
| Cognoms amb «(nom del gos)» | 173 | R-18-04(c): es neteja el cognom i el text entre parèntesis és el nom del gos |
| Fitxa sense gos | 9 | `DOG_INFERRED`: gos «Gos de {nom}» per revisar |
| Gos sense xip | 16 | `CHIP_MISSING` (el model el vol obligatori: es carrega buit i es reclama) |
| Sense email | 7 | es migra sense `Account`; convidat quan el club tingui l'adreça |
| **Email compartit amb un altre abonat** | ~20 | un `Account` per adreça: el primer es queda l'adreça i la resta es migren **sense compte** amb proposta de grup familiar (`EMAIL_SHARED`) — **dubte B33** |
| Sense telèfon | 9 | s'accepta (el model en vol 1: es carrega buit amb avís) |
| Sense codi postal / domicili | 8 / 10 | s'accepta amb avís |
| Edat per sota del mínim de l'alta (16 anys) | 2 (edats 0 i 11) | la validació d'edat **no s'aplica als migrats**: avís `AGE_SUSPECT` per revisar (probablement és la data del gos) |
| Mateix NIF en dues fitxes | 1 persona (2 fitxes) | R-18-04(a): una persona, dos gossos |
| Núm. d'abonat repetit | 4 a tot l'export | `NUMBER_CONFLICT`: guanya la fitxa d'alta |

## 7. Preguntes obertes

Les que van al Josep estan numerades com a part B del registre (`DECISIONS_PENDENTS.md`):

- **B29 · Foto**: les 145 fotos són del gos o de la persona? (proposta: del gos, que és on el producte les mostra).
- **B30 · «Quota reduïda»** (4 abonats): quina modalitat i quin preu? (proposta: `ABONAT` amb una tarifa pròpia «Quota reduïda»).
- **B31 · «Familiar Abonat/curs»** (3): és `FAMILIAR` o una modalitat diferent?
- **B32 · Nivell «Pendent»** (6 gossos): creem un nivell `PENDENT` al catàleg (no reservable fins que se'ls avaluï) o els deixem sense nivell?
- **B33 · Emails compartits** (~20): són famílies? Es poden agrupar com a grup familiar amb un sol compte?
- **Preu de «Competició 1 gos»** i confirmació que segueix viva (3 abonats).

## Canvis

- 09-09-2026 · v1.0 · primera versió a partir dels tres exports del 07-09-2026.
