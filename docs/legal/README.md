# Textos legals — esborranys (05-09-2026)

Primera versió generada a petició de Jordi (PENDENTS §2). **Són esborranys per a revisió legal**: no substitueixen l'assessorament d'un professional i no contenen encara les dades de l'empresa (marcadors `[…]`). Idioma: català; les versions `es`/`en` es generen a partir d'aquests quan estiguin validats (S11/S17 els serveixen com a `LocalizedText`).

| Fitxer | Per a què | On es mostra |
|---|---|---|
| `POLITICA_PRIVACITAT_AGILITYHUB_ID.md` | Política de l'**AgilityHub ID** (compte únic): responsable = l'empresa d'AgilityHub | `apps/id` (`/privacy`), enllaçada al login i a la pantalla «Completa el teu perfil» (S01 §14) |
| `POLITICA_PRIVACITAT_CLUB_PLANTILLA.md` | Plantilla per a cada club (responsable = el club; AgilityHub = encarregat del tractament) | URL del club (`CLUB.legal.privacyPolicyUrl`) o pàgina hostatjada `/public/{clubSlug}/privacitat` (S05/S11 «pàgines del club»); enllaç «Pots consultar-la aquí» a l'alta (19) |
| `AUTORITZACIO_IMATGE.md` | Text curt de la casella + aclariment emergent + clàusula llarga | Alta (19), fitxa D10, paràmetre `signup.text.imageConsent` |
| `NORMES_CLUB_PLANTILLA.md` | Llista inicial de normes del club, **editable des de la plataforma** (pàgina «Normes» de l'app, D11) | Pantalla 30 «Info» (pestanya Normes), enllaç a l'alta i al web del club |

## Com s'editen des de la plataforma (decisió 05-09)

Nova entitat de contingut **`ClubPage`** (`club_pages`): `key` (`RULES` · `PRIVACY` · `IMAGE_CONSENT` · `WELCOME_GUIDE` · lliure), `title: LocalizedText`, `body: LocalizedText` (Markdown limitat: títols, llistes, negreta, enllaços), `version`, `publishedAt`, `active`. Manteniment a **D11 → targeta «Pàgines del club»** (al costat de la FAQ, S05), amb editor per idioma i vista prèvia; lectura a la pantalla **30 «Info»** (pestanyes FAQ · Normes · altres pàgines actives, S11) i al web públic (`GET /public/{clubSlug}/pages/{key}`, clau d'API). La política de privacitat del club pot ser una `ClubPage` (`PRIVACY`) o una URL externa (`CLUB.legal.privacyPolicyUrl`): si la pàgina existeix, l'alta enllaça la pàgina hostatjada. Els consentiments guarden **la versió** de la `ClubPage` acceptada.

## Abans d'usar-los

1. Revisió per un advocat (RGPD + LOPDGDD 3/2018; drets d'imatge LO 1/1982; menors).
2. Omplir els marcadors: raó social, NIF, adreça, correu de contacte/DPD, dades de contacte de cada club, llista definitiva de proveïdors i ubicació dels servidors.
3. Publicar-los amb versió i data; guardar la versió acceptada a cada consentiment (S04/S14).
