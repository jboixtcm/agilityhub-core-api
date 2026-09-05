# Plantilla d'especificació per vertical (Sxx)

**v1.0 · 03-09-2026** · Copia aquest esquelet a `specs/Sxx-nom.md`. Les seccions són obligatòries (si una no aplica, s'escriu «no aplica» i el motiu). Llengua: català; noms de codi en anglès segons el glossari (`MODEL_DADES_PLATAFORMA.md` §0). Els valors concrets del Cànic s'indiquen sempre com a **valor del paràmetre** (`clau = valor`), mai com a constant.

```markdown
# Sxx — Títol del vertical

**Etapa:** Ex · **Mòduls:** `A`, `B` · **Pantalles:** 01, 02, D3 (fitxers a `03-disseny/mockups/pantalles/`) · **Model:** §… de v1.6 + §… de PLATAFORMA · **Estat:** esborrany | validada (data)

## 1. Propòsit i abast
Què resol, per a qui, i què queda explícitament fora (amb la spec on viu).

## 2. Pantalles i rutes
| Mockup | App | Ruta | Rol | Notes de comportament (estats buit/carregant/error, què fa cada botó) |

## 3. Entitats i camps
Només el que aquest vertical crea o modifica: taula camp · tipus · obligatori · validació · notes. Referència a les taules del model; no es copien senceres.

## 4. Regles de negoci
Numerades `R-xx-nn`. Cada regla: enunciat precís, **paràmetres** implicats (clau = valor Cànic), excepcions, i **exemple** amb dades fictícies. Les regles amb variants per club (ADR-012) descriuen totes les branques.

## 5. Estats i transicions
Diagrama `stateDiagram-v2` de mermaid per entitat amb estats + taula transició · qui · condició · efectes · esdeveniment.

## 6. API
| Mètode | Ruta | Rol(s) | Mòdul | Idempotent | Descripció | Cos/paràmetres clau | Respostes i errors (codis de `CONVENCIONS_API.md` §6) |
Inclou els endpoints `/me/*` i les agregacions de pantalla (un endpoint per pantalla complexa: `GET /me/home`, `GET /dashboard`).

## 7. Esdeveniments
Emesos (del catàleg) i consumits (què fa aquest vertical quan arriben).

## 8. Notificacions
Codis del `CATALEG_NOTIFICACIONS.md` que dispara, amb el moment exacte i les variables.

## 9. Paràmetres i mòduls
Claus del `CATALEG_PARAMETRES.md` que llegeix; mòduls que el condicionen i què passa amb cadascun desactivat.

## 10. i18n i localització
Claus/namespaces nous, `LocalizedText` implicats, particularitats de fus horari, gènere o perfil de país.

## 11. Criteris d'acceptació i tests obligatoris
Given/When/Then numerats `T-xx-nn`, agrupats: domini (unitaris) · integració (endpoint) · tenant/rols · concurrència · schedulers · front (component/E2E). Cada regla `R-xx-nn` ha de tenir almenys un `T-xx-nn` que la citi.

## 12. Paquets de feina (per a fils d'IA en paral·lel)
| Paquet | Repo | Depèn de | Lliurable verificable |
Ordre recomanat: contracte OpenAPI → back (domini + endpoints + tests) → front (pantalla contra mock del contracte) → integració amb seed.

## 13. Dubtes oberts
Amb qui es resol (Jordi / Josep) i què s'assumeix mentrestant.
```

## Regles d'escriptura

1. **Precisió sobre volum**: una regla ambigua val menys que cap. Si un detall no és als mockups ni al model, es proposa un valor i es marca com a **assumpció** al §13.
2. **Mockup = contracte de la UI** (`PLA_FRONTEND` §7): el §2 descriu el comportament, no redibuixa la pantalla. Es referencia el fitxer per pantalla.
3. **Res del Cànic al codi**: qualsevol «5 pistes», «nivell D», «2 h», «dia 25» s'expressa com a paràmetre, catàleg o `LocalizedText`.
4. **Vocabulari d'UI**: els literals citats a les specs són els dels mockups (`«no presentat»`, `«anul·lada tard»`, `«cancel·lada pel club»`, `«Pot entrenar sol»`, `«Compte no informat»`, `«Entra com l'abonat»`, `«Bloqueja les reserves»`). Mai «parella» en un text d'UI.
5. Cada spec cap en una sessió de vibe-coding juntament amb 1–3 pantalles: objectiu ≤ 400 línies; si creix, es parteix (Sxx-a, Sxx-b).
6. Les specs es **versionen** (capçalera) i els canvis de Josep s'apliquen igual que als mockups: fitxer nou no, però secció «Canvis» al final amb data.
