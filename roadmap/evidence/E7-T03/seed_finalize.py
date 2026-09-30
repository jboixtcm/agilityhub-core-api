"""E7-T03 step 3: writes src/main/resources/seed/message-templates.{ca,es,en}.json from the draft of seed_from_messages.py.

- The S11 §8 member texts replace the draft where §8 gives one (the report lists the three readings: N-15's «…», N-13's
  training line and N-16's S15 branch). `es`/`en` are translations of the same content (reviewed before go-live).
- ICU `gender` selectors use the lower-case keys of S11 §8/§10 (`female`, `other`): the engine lower-cases the value.
- `icon`, `color` and `matrix` come from NotificationCatalog.java (the catalog's seed icon/colour and its «Públic → canals
  per defecte» column without PUSH); `smsBody` stays only on the codes whose copy has one (every one of them can send SMS).

Run from the repository root, after seed_from_messages.py: python3 roadmap/evidence/E7-T03/seed_finalize.py
(It ran once, before messages_cleanup.py removed the member copy from messages_*; after it, N-06's title was set by hand to
S11 §8's «Reserva confirmada» — es «Reserva confirmada», en «Booking confirmed» — in the three JSON files, and N-15's SMS
got a `{mode, select, FIFO {…[[confirm_by]]…} other {§8's text}}` so the FIFO offer names its deadline, as S08 T-08-21 checks.)"""
import json
import os
import re

SOURCE = 'src/main/java/com/agilityhub/core/clubs/messaging/domain/NotificationCatalog.java'
src = open(SOURCE, encoding='utf-8').read()
catalog = {}
for chunk in src.split('rows.add(code(')[1:]:
    code = re.match(r'"([^"]+)"', chunk).group(1)
    stmt = chunk.split('.build());')[0]
    seed = re.search(r'\.seed\(TemplateIcon\.(\w+), TemplateColor\.(\w+)\)', stmt)
    matrix = {}
    for audience, channels in re.findall(r'\.to\((\w+)((?:, \w+)*)\)', stmt):
        if audience == 'APPLICANT':
            continue
        names = [c.strip() for c in channels.split(',') if c.strip()]
        matrix[audience] = {'APP': 'APP' in names, 'EMAIL': 'EMAIL' in names, 'SMS': 'SMS' in names}
    catalog[code] = dict(icon=seed.group(1) if seed else 'bell', color=seed.group(2) if seed else 'NEUTRAL', matrix=matrix)

OVERRIDES = {
    'ca': {
        'N-02': dict(title='{gender, select, female {Benvinguda} other {Benvingut}} a [[club_name]], [[member_first_name]]!',
                     body="Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: [[link]]."),
        'N-08a': dict(smsBody='[[club_name]]: la classe de [[class_date]] a les [[class_time]] ([[class_description]]) queda anul·lada. [[admin_text]]'),
        'N-15': dict(body="Classe [[class_description]] · [[class_date]] · [[class_time]]. {mode, select, FIFO {Tens fins a les [[confirm_by]] per confirmar-la.} "
                          "other {Estàs a la llista d'espera — la plaça és per a qui confirmi primer.}}",
                     smsBody="[[club_name]]: s'ha alliberat una plaça a la classe de [[class_date]] a les [[class_time]] ([[class_description]]). Entra a l'app per agafar-la."),
        'N-16': dict(body="[[class_date]] · [[class_time]] · [[class_description]], amb [[dog_name]]: la classe encara no té prou alumnes. {auto_cancel, select, "
                          "true {Si ningú més no s'hi apunta abans de les [[review_time]] de [[review_day]], la classe es cancel·larà. Et proposem reservar-ne una altra.} "
                          "other {[[review_day]] a les [[review_time]] el club decidirà si es fa.}}"),
        'N-19': dict(body="[[class_date]] no vas poder venir a la classe de [[class_description]]. Recorda que pots anul·lar des de l'app fins a última hora: "
                          "així pot aprofitar la classe algú altre. La sessió compta dins el teu còmput."),
        'N-13': dict(body='[[date]] a les [[time]] · {kind, select, TRAINING {Entrenament lliure} other {[[class_description]]}} · [[ring_name]] · amb [[dog_name]].'),
    },
    'es': {
        'N-02': dict(title='{gender, select, female {¡Bienvenida} other {¡Bienvenido}} a [[club_name]], [[member_first_name]]!',
                     body='Ya tienes acceso a la app del club. Entra con este enlace: [[link]].'),
        'N-08a': dict(smsBody='[[club_name]]: la clase del [[class_date]] a las [[class_time]] ([[class_description]]) queda cancelada. [[admin_text]]'),
        'N-15': dict(body='Clase [[class_description]] · [[class_date]] · [[class_time]]. {mode, select, FIFO {Tienes hasta las [[confirm_by]] para confirmarla.} '
                          'other {Estás en la lista de espera: la plaza es para quien la confirme primero.}}',
                     smsBody='[[club_name]]: se ha liberado una plaza en la clase del [[class_date]] a las [[class_time]] ([[class_description]]). Entra en la app para cogerla.'),
        'N-16': dict(body='[[class_date]] · [[class_time]] · [[class_description]], con [[dog_name]]: la clase todavía no tiene suficientes alumnos. {auto_cancel, select, '
                          'true {Si nadie más se apunta antes de las [[review_time]] de [[review_day]], la clase se cancelará. Te proponemos reservar otra.} '
                          'other {[[review_day]] a las [[review_time]] el club decidirá si se hace.}}'),
        'N-19': dict(body='[[class_date]] no pudiste venir a la clase de [[class_description]]. Recuerda que puedes anular desde la app hasta última hora: '
                          'así otra persona puede aprovechar la clase. La sesión cuenta en tu cómputo.'),
        'N-13': dict(body='[[date]] a las [[time]] · {kind, select, TRAINING {Entrenamiento libre} other {[[class_description]]}} · [[ring_name]] · con [[dog_name]].'),
    },
    'en': {
        'N-02': dict(title='Welcome to [[club_name]], [[member_first_name]]!', body="You now have access to the club's app. Sign in with this link: [[link]]."),
        'N-08a': dict(smsBody='[[club_name]]: the class on [[class_date]] at [[class_time]] ([[class_description]]) is cancelled. [[admin_text]]'),
        'N-15': dict(body='[[class_description]] class · [[class_date]] · [[class_time]]. {mode, select, FIFO {You have until [[confirm_by]] to confirm it.} '
                          'other {You are on the waiting list: the place goes to whoever confirms first.}}',
                     smsBody='[[club_name]]: a place has been freed up in the class on [[class_date]] at [[class_time]] ([[class_description]]). Open the app to take it.'),
        'N-16': dict(body='[[class_date]] · [[class_time]] · [[class_description]], with [[dog_name]]: the class does not have enough students yet. {auto_cancel, select, '
                          'true {If nobody else books before [[review_time]] [[review_day]], the class will be cancelled. We suggest booking another one.} '
                          'other {[[review_day]] at [[review_time]] the club will decide whether it goes ahead.}}'),
        'N-19': dict(body="On [[class_date]] you couldn't make it to the [[class_description]] class. Remember you can cancel from the app right up to the last "
                          "minute, so someone else can use the class. The session still counts towards your total."),
        'N-13': dict(body='[[date]] at [[time]] · {kind, select, TRAINING {Free training} other {[[class_description]]}} · [[ring_name]] · with [[dog_name]].'),
    },
}

os.makedirs('src/main/resources/seed', exist_ok=True)
for loc in ['ca', 'es', 'en']:
    draft = json.load(open(f'target/e7t03/draft-{loc}.json', encoding='utf-8'))
    templates = []
    for entry in draft:
        code = entry['code']
        entry.update(OVERRIDES[loc].get(code, {}))
        for key in ['title', 'body', 'smsBody']:
            if key in entry:
                entry[key] = entry[key].replace('{gender, select, FEMALE ', '{gender, select, female ')
        out = {'code': code, 'title': entry['title'], 'body': entry['body']}
        if 'smsBody' in entry:
            out['smsBody'] = entry['smsBody']
        out.update(icon=catalog[code]['icon'], color=catalog[code]['color'], matrix=catalog[code]['matrix'])
        templates.append(out)
    document = {'locale': loc, 'templates': templates}
    with open(f'src/main/resources/seed/message-templates.{loc}.json', 'w', encoding='utf-8') as f:
        json.dump(document, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(loc, len(templates), 'templates', sum(1 for t in templates if 'smsBody' in t), 'with smsBody')
