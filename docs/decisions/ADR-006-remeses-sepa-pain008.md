# ADR-006 — Generació del fitxer SEPA de remeses (pain.008)

**Estat:** Acceptada
**Data:** 2026-10-05 (decisió E90; implementació a `agilityhub-core-api` E8-T03, verificada el mateix dia)
**Decisors:** Jordi (amb assistència IA); proposta redactada per l'executor d'E8-T03 i revisada per l'organitzador

## Context

La remesa mensual del Cànic (S12 R-12-11…R-12-17, ADR-009 proveïdor `SEPA_XML`) és un fitxer **pain.008** de dèbit directe SEPA que el banc del club ha d'acceptar sense esmenes. El fitxer porta IBAN complets, s'ha de poder reproduir (dos execucions sobre el mateix cens donen els mateixos bytes) i ha de quedar auditat (S12 `Remittance.xsdValidatedAt`). Les regles de negoci que l'envolten ja estan decidides: cobrament el dia 1 del mes facturat, tots els mandats `RCUR` (Josep 08-09; Playoff no exporta mandats), rollback manual abans de l'enviament.

El banc del club **encara no ha lliurat el seu XSD propi** ni ha respost si exigeix `FRST` per als mandats nous (pendent de Jordi, `DECISIONS_PENDENTS.md` part A). Calia decidir com es genera i es valida el fitxer sense esperar-lo.

## Opcions considerades

1. **Classes JAXB generades amb `xjc` a partir de l'XSD** (en temps de build, `jaxb2-maven-plugin` → paquet `payments.sepa.generated`), fitxer validat contra el mateix XSD abans de desar-se.
   Pros: el compilador garanteix l'estructura; la validació i la generació usen la mateixa font; si el banc envia el seu XSD, es canvia un fitxer i es regenera. Contres: els noms de tipus depenen de l'XSD concret (el DK té `GroupHeaderSDD`, l'ISO pur `GroupHeader39`); el build depèn d'un plugin més.
2. **Llibreria SEPA externa** (p. ex. les que empaqueten pain.008 a Java).
   Pros: menys codi propi. Contres: una dependència més amb les seves pròpies restriccions i ritme de versions; el fitxer resultant s'hauria de validar igualment contra l'XSD del banc; menys control sobre l'ordre i el format dels camps (reproductibilitat).
3. **XML construït a mà** (plantilla o `XMLStreamWriter`).
   Pros: cap dependència. Contres: cap garantia estructural en temps de compilació; els errors apareixen al banc; cada canvi d'esquema és manual.

## Decisió

**Opció 1.** Les classes es generen en temps de build a partir de l'XSD del repositori; els bytes del fitxer es validen contra aquest mateix XSD **dins de la transacció de la generació**, abans que el fitxer es desi o res es confirmi; els fitxers daurats (golden files, T-12-11) són la xarxa de regressió sobre una sortida determinista. Una llibreria externa només seria acceptable si passés la mateixa validació i els mateixos fitxers daurats.

**Esquema en ús mentre no arriba el del banc:** la restricció SEPA pública de la **Deutsche Kreditwirtschaft (DK)** de pain.008.001.02 (DFÜ-Abkommen Anlage 3 v3.0, EPC SDD Core IG 9.0 i B2B IG 7.0, espai de noms ISO 2009), extreta de l'artefacte de Maven Central `com.github.hbci4j:hbci4j-core:4.1.17` i desada a `src/main/resources/sepa/pain.008.001.02.xsd` (només normalització de finals de línia). És més estricta que l'esquema ISO nu en els punts que un banc SEPA comprova (`Nm` ≤ 70, només EUR, identificadors al joc de caràcters SEPA, `MndtRltdInf` i `DtOfSgntr` obligatoris, BIC o `NOTPROVIDED`), i un fitxer vàlid contra ella ho és contra l'ISO. Es pot mantenir al repositori: la llicència de l'artefacte és compatible i l'XSD és un document públic de la DK.

## Conseqüències

- L'XSD és una **entrada del build**: canviar-lo regenera les classes i els fitxers daurats, sempre de manera deliberada i amb una nota al report de la tasca. `billing.sepa.schema` anomena l'esquema; passar a `.08` afegiria el seu propi XSD, classes i fitxers daurats.
- El mapatge (`Pain008Document`) està lligat als noms de tipus del DK; si el banc lliura el seu XSD amb noms diferents, només aquesta classe s'adapta (E8-T03 report, «Open point»).
- Els identificadors que entren al fitxer (sèrie de factura, `MsgId`, `PmtInfId`, `EndToEndId`, creditor) es comproven **abans** de serialitzar, amb error `422 SEPA_NOT_CONFIGURED {reason: IDENTIFIER}` i la restricció documentada a `billing.invoiceSeriesPattern` (E8-T08); la transliteració cobreix `’ → '` i `– — → -`.
- El mandat pertany a l'intent de cobrament (`Collection`), no al rebut: un rebut en espera es remet amb el mandat **vigent** del soci, i el cens conserva la seqüència de mandats quan el soci canvia de mètode i torna a SEPA (E90, E8-T08).
- Fins que el banc no respongui, `useFrst = false` i tot és `RCUR` (Josep 08-09). Si el banc exigeix `FRST`, és un paràmetre, no un canvi d'esquema.
- **Pendent (Jordi):** l'XSD propi del banc i la resposta sobre `FRST`. Són elements de release, no bloquegen E8.
