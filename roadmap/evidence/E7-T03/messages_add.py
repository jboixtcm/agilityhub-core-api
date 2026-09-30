"""E7-T03 step 10: the back-end keys of D9, the preview's fictional data set, the enum labels and the log export columns,
appended to messages_{ca,es,en}.properties in one go (the parity test compares the three files).

Run from the repository root once: python3 roadmap/evidence/E7-T03/messages_add.py"""

VARIABLES = {
    'activity_title': ('activitat_titol', 'actividad_titulo', 'activity_title'),
    'actor': ('autor_canvi', 'autor_cambio', 'change_author'),
    'admin_text': ('text_admin', 'texto_admin', 'admin_text'),
    'amount': ('import', 'importe', 'amount'),
    'audience': ('public', 'publico', 'audience'),
    'auto_cancel': ('anulacio_automatica', 'cancelacion_automatica', 'auto_cancel'),
    'calendar_links': ('enllac_calendari', 'enlace_calendario', 'calendar_link'),
    'cancelled_count': ('classes_anulades', 'clases_anuladas', 'cancelled_classes'),
    'cap': ('limit_sms', 'limite_sms', 'sms_limit'),
    'challenge_title': ('repte_titol', 'reto_titulo', 'challenge_title'),
    'change': ('tipus_canvi', 'tipo_cambio', 'change_type'),
    'changes': ('canvis', 'cambios', 'changes'),
    'class_date': ('classe_data', 'clase_fecha', 'class_date'),
    'class_description': ('classe_descripcio', 'clase_descripcion', 'class_description'),
    'class_time': ('classe_hora', 'clase_hora', 'class_time'),
    'club_name': ('entitat_nom', 'entidad_nombre', 'club_name'),
    'concept': ('concepte', 'concepto', 'concept'),
    'confirm_by': ('confirmar_abans', 'confirmar_antes', 'confirm_by'),
    'count': ('nombre', 'numero', 'count'),
    'date': ('data', 'fecha', 'date'),
    'decision': ('decisio', 'decision', 'decision'),
    'document_type': ('document_tipus', 'documento_tipo', 'document_type'),
    'dog_name': ('gos_nom', 'perro_nombre', 'dog_name'),
    'dog_name_article': ('gos_nom_amb_article', 'perro_nombre_articulo', 'dog_name_article'),
    'dogs': ('gossos', 'perros', 'dogs'),
    'dogs_count': ('gossos_inscrits', 'perros_inscritos', 'dogs_booked'),
    'effective_date': ('persona_data_baixa', 'persona_fecha_baja', 'person_leaving_date'),
    'email': ('correu', 'correo', 'email'),
    'entityId': ('referencia', 'referencia', 'reference'),
    'error_count': ('errors', 'errores', 'errors'),
    'execute_date': ('data_execucio', 'fecha_ejecucion', 'run_date'),
    'expires_days': ('dies_validesa', 'dias_validez', 'valid_days'),
    'expires_minutes': ('minuts_validesa', 'minutos_validez', 'valid_minutes'),
    'fee': ('quota', 'cuota', 'fee'),
    'from_month': ('mes_inici', 'mes_inicio', 'from_month'),
    'gender': ('persona_genere', 'persona_genero', 'person_gender'),
    'host': ('domini', 'dominio', 'domain'),
    'instructor_name': ('instructor_nom', 'instructor_nombre', 'instructor_name'),
    'inviter_name': ('convidant_nom', 'invitante_nombre', 'inviter_name'),
    'invoice_number': ('rebut_numero', 'recibo_numero', 'invoice_number'),
    'job_name': ('proces_nom', 'proceso_nombre', 'process_name'),
    'kind': ('tipus', 'tipo', 'kind'),
    'late': ('fora_de_termini', 'fuera_de_plazo', 'late'),
    'level': ('nivell', 'nivel', 'level'),
    'level_name': ('gos_nivell', 'perro_nivel', 'dog_level'),
    'link': ('enllac', 'enlace', 'link'),
    'masked_account': ('compte_emmascarat', 'cuenta_oculta', 'masked_account'),
    'member_first_name': ('persona_nom', 'persona_nombre', 'person_first_name'),
    'member_last_names': ('persona_cognoms', 'persona_apellidos', 'person_last_names'),
    'member_name': ('persona_nom_complet', 'persona_nombre_completo', 'person_full_name'),
    'mode': ('modalitat_espera', 'modalidad_espera', 'waitlist_mode'),
    'month': ('mes', 'mes', 'month'),
    'oldest_days': ('dies_mes_antiga', 'dias_mas_antigua', 'oldest_days'),
    'pack_expiry': ('bo_caducitat', 'bono_caducidad', 'pack_expiry'),
    'pack_remaining': ('bo_sessions', 'bono_sesiones', 'pack_sessions_left'),
    'pay_link': ('enllac_pagament', 'enlace_pago', 'payment_link'),
    'payment_instructions': ('instruccions_pagament', 'instrucciones_pago', 'payment_instructions'),
    'pending_count': ('rebuts_pendents', 'recibos_pendientes', 'pending_invoices'),
    'period': ('periode', 'periodo', 'period'),
    'plan_name': ('modalitat', 'modalidad', 'plan'),
    'reason': ('motiu', 'motivo', 'reason'),
    'requested_date': ('data_demanada', 'fecha_solicitada', 'requested_date'),
    'retry_link': ('enllac_reintent', 'enlace_reintento', 'retry_link'),
    'review_day': ('revisio_dia', 'revision_dia', 'review_day'),
    'review_time': ('revisio_hora', 'revision_hora', 'review_time'),
    'ring_name': ('pista_nom', 'pista_nombre', 'ring_name'),
    'role': ('rol', 'rol', 'role'),
    'score': ('puntuacio', 'puntuacion', 'score'),
    'setup_kind': ('recorregut_tipus', 'recorrido_tipo', 'course_kind'),
    'source': ('origen', 'origen', 'source'),
    'state': ('estat', 'estado', 'state'),
    'task_excerpt': ('tasca_text', 'tarea_texto', 'task_text'),
    'time': ('hora', 'hora', 'time'),
    'to_month': ('mes_final', 'mes_fin', 'to_month'),
    'upfront_total': ('import_inicial', 'importe_inicial', 'upfront_total'),
    'week_start': ('setmana_inici', 'semana_inicio', 'week_start'),
}

PREVIEW = {
    'memberFirstName': ('Laura', 'Laura', 'Laura'),
    'memberLastNames': ('Serra Puig', 'Serra Puig', 'Serra Puig'),
    'dogName': ('Duna', 'Duna', 'Duna'),
    'secondDogName': ('Rock', 'Rock', 'Rock'),
    'levelName': ('C', 'C', 'C'),
    'secondLevelName': ('D', 'D', 'D'),
    'classDescription': ('B+C', 'B+C', 'B+C'),
    'ringName': ('Central', 'Central', 'Central'),
    'adminText': ("La classe queda anul·lada per la pluja. Podeu reservar-ne una altra des de l'app. Disculpeu les molèsties!",
                  'La clase queda cancelada por la lluvia. Podéis reservar otra desde la app. ¡Disculpad las molestias!',
                  'The class is cancelled because of the rain. You can book another one in the app. Sorry for the inconvenience!'),
    'reason': ('Documentació pendent', 'Documentación pendiente', 'Pending documents'),
    'concept': ("Quota d'agost", 'Cuota de agosto', 'August fee'),
    'planName': ('Quota mensual', 'Cuota mensual', 'Monthly fee'),
    'paymentInstructions': ('Pagament en efectiu a la recepció del club', 'Pago en efectivo en la recepción del club', "Cash payment at the club's reception"),
    'instructorName': ('Marta', 'Marta', 'Marta'),
    'taskExcerpt': ('Treballar el contacte a la zona de salts', 'Trabajar el contacto en la zona de saltos', 'Work on the contact in the jumps area'),
    'documentType': ('Vacuna antiràbica', 'Vacuna antirrábica', 'Rabies vaccine'),
    'activityTitle': ('Seminari de canicross', 'Seminario de canicross', 'Canicross seminar'),
    'jobName': ('Avisos de no presentat', 'Avisos de no presentado', 'No-show notices'),
    'email': ('laura@example.test', 'laura@example.test', 'laura@example.test'),
}

ENUMS = {
    'notificationCategory': {
        'OPERATIONAL': ('Operativa', 'Operativa', 'Operational'),
        'PERSONAL': ('Comunicats individuals', 'Comunicados individuales', 'Personal notices'),
        'CLUB_CHANGES': ('Canvis en reserves', 'Cambios en reservas', 'Booking changes'),
        'CLUB_NEWS': ('Comunicats del club', 'Comunicados del club', 'Club news'),
        'SYSTEM': ('Compte', 'Cuenta', 'Account'),
    },
    'deliveryStatus': {
        'QUEUED': ('En cua', 'En cola', 'Queued'),
        'SENT': ('Enviat', 'Enviado', 'Sent'),
        'DELIVERED': ('Lliurat', 'Entregado', 'Delivered'),
        'FAILED': ('Error', 'Error', 'Failed'),
        'SKIPPED_BY_PREFERENCE': ('No enviat (preferència)', 'No enviado (preferencia)', 'Not sent (preference)'),
        'SKIPPED_MODULE_OFF': ('No enviat (canal desactivat)', 'No enviado (canal desactivado)', 'Not sent (channel off)'),
        'SKIPPED_NO_CONTACT': ('No enviat (sense contacte)', 'No enviado (sin contacto)', 'Not sent (no contact)'),
        'SKIPPED_CAP': ("No enviat (límit d'SMS)", 'No enviado (límite de SMS)', 'Not sent (SMS limit)'),
        'SKIPPED_STALE': ('No enviat (ja no calia)', 'No enviado (ya no era necesario)', 'Not sent (no longer needed)'),
        'SKIPPED_NOT_ALLOWED': ('No enviat (número no permès)', 'No enviado (número no permitido)', 'Not sent (number not allowed)'),
    },
    'notificationChannel': {
        'APP': ('App', 'App', 'App'),
        'EMAIL': ('Correu', 'Correo', 'Email'),
        'SMS': ('SMS', 'SMS', 'SMS'),
        'PUSH': ('Notificació al mòbil', 'Notificación en el móvil', 'Phone notification'),
    },
    'templateIcon': {
        'check': ('Fet', 'Hecho', 'Done'), 'x': ('Creu', 'Cruz', 'Cross'), 'unlock': ('Cadenat obert', 'Candado abierto', 'Unlocked'),
        'warn': ('Avís', 'Aviso', 'Warning'), 'up': ('Amunt', 'Arriba', 'Up'), 'heart': ('Cor', 'Corazón', 'Heart'),
        'bell': ('Campaneta', 'Campana', 'Bell'), 'doc': ('Document', 'Documento', 'Document'), 'flag': ('Bandera', 'Bandera', 'Flag'),
        'mail': ('Correu', 'Correo', 'Mail'), 'cal': ('Calendari', 'Calendario', 'Calendar'), 'clock': ('Rellotge', 'Reloj', 'Clock'),
        'info': ('Informació', 'Información', 'Information'), 'paw': ('Pota', 'Pata', 'Paw'), 'cone': ('Con', 'Cono', 'Cone'),
        'lock': ('Cadenat', 'Candado', 'Lock'),
    },
    'templateColor': {
        'NEUTRAL': ('Neutre', 'Neutro', 'Neutral'), 'OK': ('Correcte', 'Correcto', 'Success'), 'WARNING': ('Avís', 'Aviso', 'Warning'),
        'ERROR': ('Error', 'Error', 'Error'), 'ACCENT': ('Color del club', 'Color del club', 'Club colour'),
    },
}

EXPORT = {
    'createdAt': ('Data', 'Fecha', 'Date'), 'code': ('Avís', 'Aviso', 'Notice'), 'recipient': ('Destinatari', 'Destinatario', 'Recipient'),
    'channels': ('Canals', 'Canales', 'Channels'), 'readAt': ('Llegida', 'Leída', 'Read'),
}


def escape(text):
    # Properties are read as UTF-8 (IcuMessageSource); an apostrophe is literal in ICU's DOUBLE_OPTIONAL mode.
    return text.replace('\\', '\\\\')


for index, loc in enumerate(['ca', 'es', 'en']):
    lines = ['', '# E7-T03 (S11 §10): D9 variable chips, the preview data set, enum labels and the notification log export.']
    for key in sorted(VARIABLES):
        lines.append(f'notif.variable.{key}={escape(VARIABLES[key][index])}')
    for key in sorted(PREVIEW):
        lines.append(f'notif.preview.{key}={escape(PREVIEW[key][index])}')
    for enum, values in ENUMS.items():
        for key, labels in values.items():
            lines.append(f'enums.{enum}.{key}={escape(labels[index])}')
    for key, labels in EXPORT.items():
        lines.append(f'export.column.notifications.{key}={escape(labels[index])}')
    path = f'src/main/resources/messages/messages_{loc}.properties'
    content = open(path, encoding='utf-8').read()
    if 'notif.variable.' in content:
        raise SystemExit(path + ' already has the E7-T03 keys')
    if not content.endswith('\n'):
        content += '\n'
    open(path, 'w', encoding='utf-8').write(content + '\n'.join(lines[1:]) + '\n')
    print(loc, len(lines) - 2, 'keys added')
