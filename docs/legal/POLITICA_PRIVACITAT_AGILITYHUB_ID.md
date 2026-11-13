# Política de privacitat de la plataforma (compte d'accés i dades del club)

**Versió:** 2 · **Data d'entrada en vigor:** pendent (es fixa quan el club obri la plataforma)

> **Text definitiu** que `apps/id` serveix a `/privacy` en català, castellà i anglès. La font és `apps/id/src/privacy/privacy-policy.ts` (`PRIVACY_POLICY_VERSION`, `PRIVACY_POLICY_EFFECTIVE_DATE`); un test comprova que aquest document en porta el mateix text. Decisió del propietari del 13-11-2026 (PRIV-01, auditoria de preproducció): mentre la plataforma serveix un sol club, el club és el responsable de totes les dades (el compte d'accés i les del club) i AgilityHub n'és l'encarregat, amb DigitalOcean, Brevo i Stripe; el banc del club rep les remeses SEPA.
>
> - **No l'ha redactat un advocat: cal la revisió legal del club** abans de fixar-ne la data d'entrada en vigor.
> - La data d'entrada en vigor és una constant pendent: una build de producció d'`apps/id` falla mentre no es fixi.
> - El servidor ha de portar la mateixa versió (`PLATFORM_PRIVACY_POLICY_VERSION=2`) i aquesta adreça (`PLATFORM_PRIVACY_POLICY_URL=https://id.agilitydoghub.com/privacy`). Un canvi del text vol dir pujar la versió als dos llocs, i tots els comptes l'hauran de tornar a acceptar.
> - Els terminis de conservació són els valors actuals dels paràmetres del club (`rgpd.retentionYearsAfterLeave` 6, `audit.retentionYears` 6, `rgpd.erasureMinDaysAfterLeave` 30, `rgpd.rejectedSignupRetentionDays` 365, `security.eventRetentionDays` 90) i els 30 dies de les còpies de seguretat (`deploy/backup/lifecycle.json` de l'api): si se'n canvia algun, cal canviar el text.

## Català

Aquesta política explica com es tracten les teves dades personals a la plataforma de gestió que fa servir el club: l'app per als abonats, l'administració del club i el teu compte d'accés.

# 1. Qui és el responsable del tractament

- **Responsable:** Club Agility Cànic
- **NIF:** G63189617
- **Adreça:** Carrer Sant Pere, 10, 08392 Sant Andreu de Llavaneres
- **Contacte de privacitat:** [info@agilitycanic.cat](mailto:info@agilitycanic.cat)

Ara mateix la plataforma només dona servei a aquest club, que és el responsable de totes les dades que s'hi tracten: les del teu compte d'accés i les de la teva relació amb el club. AgilityHub és el proveïdor tecnològic que desenvolupa i manté la plataforma, i tracta les dades només per encàrrec del club.

# 2. Quines dades tractem

- **Compte d'accés:** correu electrònic, nom, idioma, contrasenya si en tries una (se'n guarda només un resum irreversible), els perfils que tens al club (alumnat, instrucció o administració) i els dispositius amb la sessió oberta, amb la data del darrer ús.
- **Fitxa d'abonat:** nom i cognoms, document d'identitat, data de naixement, gènere, correus i telèfons de contacte, adreça postal, número d'abonat, modalitat i tarifa, dates d'alta i de baixa, grup familiar i les observacions del club.
- **Gossos:** nom, sexe, raça, data de naixement, número de xip, foto, cartilla de vacunes i altres documents, nivell i el seu historial, llicències, les notes que deixes per als instructors i les observacions dels instructors.
- **Activitat al club:** reserves de classes i d'entrenaments, llistes d'espera, assistència, inscripcions a activitats, packs, períodes d'inactivitat, sol·licituds de baixa i el seguiment que en fan els instructors.
- **Pagaments:** rebuts i cobraments. Per a la domiciliació, l'IBAN (guardat xifrat), el titular del compte i el seu NIF, i la referència i la data del mandat SEPA. Per als pagaments amb targeta, l'identificador de client a Stripe, la marca i els quatre últims dígits de la targeta.
- **Consentiments:** l'acceptació de la política de privacitat i l'autorització d'ús de la imatge, amb la versió i la data; els que dones en el formulari d'alta, també amb l'idioma i una empremta irreversible de l'adreça IP des d'on es van donar.
- **Comunicacions:** els avisos que reps (a l'app, per correu, per SMS o com a notificació al dispositiu), el seu estat d'entrega, les teves preferències d'avisos i, si actives les notificacions en un dispositiu, l'adreça de subscripció que en dona el navegador.
- **Seguretat i registre de canvis:** els intents d'accés fallits i altres esdeveniments de seguretat, amb l'adreça IP i el navegador, i el registre dels canvis que es fan a les dades, tant els de l'equip del club com els que hi fas tu (qui, què i quan, amb l'adreça IP i el navegador).

Aquestes dades les facilites tu (en apuntar-te, a l'app o directament al club) o les introdueix l'equip del club; les que el club tenia al programa de gestió que feia servir abans es traspassen a aquesta plataforma. Les dades tècniques (sessions, avisos i registres de seguretat) es generen en fer-la servir. Les dades marcades com a obligatòries al formulari d'alta són necessàries per gestionar l'abonament: sense elles, el club no pot tramitar l'alta.

# 3. Per a què les fem servir i amb quina base jurídica

- **Gestionar la teva relació amb el club** (alta, modalitat, classes, reserves, entrenaments, activitats, assistència, nivells dels gossos, inactivitats i baixes) i **donar-te accés a la plataforma**: execució del contracte d'abonat (art. 6.1.b del RGPD).
- **Cobrar les quotes i els pagaments** per domiciliació, amb targeta o en efectiu, i portar la comptabilitat del club: execució del contracte i compliment de les obligacions comptables i fiscals (art. 6.1.b i 6.1.c).
- **Enviar-te els avisos del servei** (reserves, canvis i anul·lacions de classes, places que queden lliures, rebuts i enllaços d'accés): execució del contracte.
- **Enviar-te els comunicats del club**: interès legítim del club a informar els seus abonats (art. 6.1.f). Pots deixar de rebre'ls per correu des del perfil de l'app o des de l'enllaç que porta cada correu.
- **Enviar-te notificacions al dispositiu**: només si les actives i hi dones permís al navegador. Les pots desactivar quan vulguis.
- **Registrar si autoritzes el club a publicar fotos i vídeos on apareguis tu o el teu gos**: el teu consentiment (art. 6.1.a), que pots retirar quan vulguis.
- **Protegir la plataforma i els comptes** (detecció d'accessos indeguts, bloqueig temporal després de massa intents fallits, registre de canvis): interès legítim del club en la seguretat de les dades (art. 6.1.f).
- **Atendre els teus drets i els requeriments de les autoritats**: compliment d'obligacions legals (art. 6.1.c).

No elaborem perfils comercials ni venem les teves dades. Els processos automàtics de la plataforma només apliquen les normes del club, com la caducitat d'un pack o l'anul·lació d'una classe sense prou inscrits.

# 4. Qui hi té accés i a qui les comuniquem

- **L'equip del club:** les persones que l'administren veuen les dades que necessiten per gestionar el club, i els instructors, les que necessiten per fer les classes (per exemple, els abonats i els gossos de les classes, els nivells i l'assistència).
- **AgilityHub**, proveïdor tecnològic de la plataforma, com a encarregat del tractament (art. 28 del RGPD): tracta les dades només per prestar el servei al club i donar-li suport tècnic. Quan el seu equip ha d'entrar a l'administració del club per resoldre una incidència, ho fa amb un accés temporal que queda registrat.
- **DigitalOcean**, encarregat del tractament: allotjament dels servidors, de la base de dades, dels fitxers i de les còpies de seguretat, en centres de dades de la Unió Europea.
- **Brevo**, encarregat del tractament: enviament dels correus electrònics i dels SMS.
- **Stripe**, encarregat del tractament: pagaments amb targeta, també els cobraments periòdics amb la targeta que hi deixis guardada. Les dades de la targeta s'introdueixen a les pàgines de Stripe: la plataforma no rep mai el número complet.
- **El banc del club**, que rep les remeses dels rebuts domiciliats (titular, IBAN, referència del mandat i import) per cobrar-los.
- **Les autoritats públiques**, quan una llei ho exigeixi.

Si actives les notificacions en un dispositiu, el missatge hi arriba a través del servei de notificacions del fabricant del navegador o del sistema (per exemple, Google, Apple, Mozilla o Microsoft), xifrat de manera que aquest servei no en pot llegir el contingut.

Les dades s'allotgen a la Unió Europea. DigitalOcean i Stripe formen part de grups amb seu als Estats Units: si alguna dada s'hi tracta des de fora de l'Espai Econòmic Europeu, es fa amb les garanties que preveu el RGPD, com les clàusules contractuals tipus de la Comissió Europea o el Marc de privacitat de dades UE-EUA.

# 5. Quant de temps les conservem

- **Mentre siguis abonat**, conservem les dades necessàries per a la teva relació amb el club.
- **Després de la baixa**, el club conserva les teves dades **6 anys**, el termini durant el qual ha de guardar la documentació comptable (art. 30 del Codi de comerç) i pot haver d'atendre reclamacions. Passat aquest termini, la plataforma les pseudonimitza automàticament, també les del teu compte d'accés: el nom, les dades de contacte, el document d'identitat, l'IBAN i la resta de dades que t'identifiquen s'esborren o se substitueixen per un identificador, i només es conserven, sense aquestes dades, els registres que la comptabilitat del club necessita (rebuts, imports i dates).
- **Si demanes la supressió** després de la baixa, el club la fa de la mateixa manera, passats com a mínim 30 dies des de la baixa i quan no queden rebuts ni pagaments pendents.
- **Si el club rebutja una sol·licitud d'alta**, les dades de la sol·licitud es pseudonimitzen 1 any després del rebuig.
- **El registre de canvis** es conserva 6 anys, i **els fitxers de les remeses** enviades al banc, 6 anys des de la seva data.
- **Els esdeveniments de seguretat** es conserven 90 dies.
- **Els paquets de dades que descarreguis** estan disponibles 7 dies i després s'esborren.
- **Les còpies de seguretat**, xifrades, es conserven fins a 30 dies i no es modifiquen: les dades suprimides en desapareixen quan les còpies caduquen.

# 6. Els teus drets

Pots exercir en qualsevol moment els drets d'**accés, rectificació, supressió, oposició, limitació del tractament i portabilitat**, i **retirar els consentiments** que hagis donat:

- A l'app pots consultar les teves dades, corregir les de contacte i l'adreça, i gestionar la foto, les notes i els documents dels teus gossos.
- Des del perfil de l'app pots descarregar un paquet amb les teves dades («Descarrega les meves dades»).
- A la pàgina «El teu compte» d'aquest lloc pots canviar el nom, l'idioma i la contrasenya, i tancar les sessions obertes.
- Per a qualsevol altra petició, escriu a [info@agilitycanic.cat](mailto:info@agilitycanic.cat). Si cal, el club et pot demanar que acreditis la teva identitat. Et respondrem en el termini d'un mes.

La supressió es fa un cop tramitada la baixa del club (apartat 5). Si consideres que no s'han atès correctament els teus drets, pots presentar una reclamació a l'**Agència Espanyola de Protecció de Dades** ([www.aepd.es](https://www.aepd.es)).

# 7. Seguretat

La plataforma només funciona amb connexions xifrades (HTTPS). Les contrasenyes es guarden amb un algorisme de resum robust i mai en clar; quan en tries una, comprovem que no aparegui en filtracions conegudes enviant al servei públic Have I Been Pwned només els cinc primers caràcters del seu resum, mai la contrasenya. Els IBAN es guarden xifrats, els enllaços d'accés per correu són d'un sol ús i caduquen, l'accés es bloqueja temporalment després de massa intents fallits, pots tancar les sessions obertes des del teu compte, els canvis que fa l'equip del club queden registrats i les còpies de seguretat es xifren.

# 8. Menors

Si l'abonat és menor de 14 anys, les seves dades les facilita i els seus drets els exerceix el pare, la mare o el tutor legal, i el club en recull l'autorització.

# 9. Galetes i emmagatzematge al navegador

La plataforma només fa servir galetes i emmagatzematge local tècnics, necessaris perquè funcioni: mantenir la sessió iniciada i recordar l'idioma i algunes preferències de pantalla. No fa servir galetes publicitàries ni d'analítica, i per això no et demana cap consentiment per a galetes.

# 10. Canvis d'aquesta política

Si aquesta política canvia, la plataforma et demanarà que llegeixis i acceptis la versió nova en entrar; la versió que acceptis i la data queden registrades al teu compte.

## Castellano

Esta política explica cómo se tratan tus datos personales en la plataforma de gestión que utiliza el club: la app para los abonados, la administración del club y tu cuenta de acceso.

# 1. Quién es el responsable del tratamiento

- **Responsable:** Club Agility Cànic
- **NIF:** G63189617
- **Dirección:** Carrer Sant Pere, 10, 08392 Sant Andreu de Llavaneres
- **Contacto de privacidad:** [info@agilitycanic.cat](mailto:info@agilitycanic.cat)

Por ahora la plataforma solo da servicio a este club, que es el responsable de todos los datos que se tratan en ella: los de tu cuenta de acceso y los de tu relación con el club. AgilityHub es el proveedor tecnológico que desarrolla y mantiene la plataforma, y trata los datos solo por encargo del club.

# 2. Qué datos tratamos

- **Cuenta de acceso:** correo electrónico, nombre, idioma, contraseña si eliges una (solo se guarda un resumen irreversible), los perfiles que tienes en el club (alumnado, instrucción o administración) y los dispositivos con la sesión abierta, con la fecha del último uso.
- **Ficha de abonado:** nombre y apellidos, documento de identidad, fecha de nacimiento, género, correos y teléfonos de contacto, dirección postal, número de abonado, modalidad y tarifa, fechas de alta y de baja, grupo familiar y las observaciones del club.
- **Perros:** nombre, sexo, raza, fecha de nacimiento, número de microchip, foto, cartilla de vacunación y otros documentos, nivel y su historial, licencias, las notas que dejas para los instructores y las observaciones de los instructores.
- **Actividad en el club:** reservas de clases y de entrenamientos, listas de espera, asistencia, inscripciones a actividades, packs, periodos de inactividad, solicitudes de baja y el seguimiento que hacen los instructores.
- **Pagos:** recibos y cobros. Para la domiciliación, el IBAN (guardado cifrado), el titular de la cuenta y su NIF, y la referencia y la fecha del mandato SEPA. Para los pagos con tarjeta, el identificador de cliente en Stripe, la marca y los cuatro últimos dígitos de la tarjeta.
- **Consentimientos:** la aceptación de la política de privacidad y la autorización de uso de la imagen, con la versión y la fecha; los que das en el formulario de alta, también con el idioma y una huella irreversible de la dirección IP desde la que se dieron.
- **Comunicaciones:** los avisos que recibes (en la app, por correo, por SMS o como notificación en el dispositivo), su estado de entrega, tus preferencias de avisos y, si activas las notificaciones en un dispositivo, la dirección de suscripción que facilita el navegador.
- **Seguridad y registro de cambios:** los intentos de acceso fallidos y otros eventos de seguridad, con la dirección IP y el navegador, y el registro de los cambios que se hacen en los datos, tanto los del equipo del club como los que haces tú (quién, qué y cuándo, con la dirección IP y el navegador).

Estos datos los facilitas tú (al apuntarte, en la app o directamente en el club) o los introduce el equipo del club; los que el club tenía en el programa de gestión que usaba antes se traspasan a esta plataforma. Los datos técnicos (sesiones, avisos y registros de seguridad) se generan al utilizarla. Los datos marcados como obligatorios en el formulario de alta son necesarios para gestionar el abono: sin ellos, el club no puede tramitar el alta.

# 3. Para qué los usamos y con qué base jurídica

- **Gestionar tu relación con el club** (alta, modalidad, clases, reservas, entrenamientos, actividades, asistencia, niveles de los perros, inactividades y bajas) y **darte acceso a la plataforma**: ejecución del contrato de abonado (art. 6.1.b del RGPD).
- **Cobrar las cuotas y los pagos** por domiciliación, con tarjeta o en efectivo, y llevar la contabilidad del club: ejecución del contrato y cumplimiento de las obligaciones contables y fiscales (art. 6.1.b y 6.1.c).
- **Enviarte los avisos del servicio** (reservas, cambios y anulaciones de clases, plazas que quedan libres, recibos y enlaces de acceso): ejecución del contrato.
- **Enviarte los comunicados del club**: interés legítimo del club en informar a sus abonados (art. 6.1.f). Puedes dejar de recibirlos por correo desde el perfil de la app o desde el enlace que lleva cada correo.
- **Enviarte notificaciones al dispositivo**: solo si las activas y das permiso en el navegador. Puedes desactivarlas cuando quieras.
- **Registrar si autorizas al club a publicar fotos y vídeos en los que aparezcas tú o tu perro**: tu consentimiento (art. 6.1.a), que puedes retirar cuando quieras.
- **Proteger la plataforma y las cuentas** (detección de accesos indebidos, bloqueo temporal tras demasiados intentos fallidos, registro de cambios): interés legítimo del club en la seguridad de los datos (art. 6.1.f).
- **Atender tus derechos y los requerimientos de las autoridades**: cumplimiento de obligaciones legales (art. 6.1.c).

No elaboramos perfiles comerciales ni vendemos tus datos. Los procesos automáticos de la plataforma solo aplican las normas del club, como la caducidad de un pack o la anulación de una clase sin suficientes inscritos.

# 4. Quién tiene acceso y a quién los comunicamos

- **El equipo del club:** las personas que lo administran ven los datos que necesitan para gestionar el club, y los instructores, los que necesitan para dar las clases (por ejemplo, los abonados y los perros de las clases, los niveles y la asistencia).
- **AgilityHub**, proveedor tecnológico de la plataforma, como encargado del tratamiento (art. 28 del RGPD): trata los datos solo para prestar el servicio al club y darle soporte técnico. Cuando su equipo tiene que entrar en la administración del club para resolver una incidencia, lo hace con un acceso temporal que queda registrado.
- **DigitalOcean**, encargado del tratamiento: alojamiento de los servidores, de la base de datos, de los archivos y de las copias de seguridad, en centros de datos de la Unión Europea.
- **Brevo**, encargado del tratamiento: envío de los correos electrónicos y de los SMS.
- **Stripe**, encargado del tratamiento: pagos con tarjeta, también los cobros periódicos con la tarjeta que dejes guardada. Los datos de la tarjeta se introducen en las páginas de Stripe: la plataforma nunca recibe el número completo.
- **El banco del club**, que recibe las remesas de los recibos domiciliados (titular, IBAN, referencia del mandato e importe) para cobrarlos.
- **Las autoridades públicas**, cuando una ley lo exija.

Si activas las notificaciones en un dispositivo, el mensaje llega a través del servicio de notificaciones del fabricante del navegador o del sistema (por ejemplo, Google, Apple, Mozilla o Microsoft), cifrado de forma que ese servicio no puede leer su contenido.

Los datos se alojan en la Unión Europea. DigitalOcean y Stripe forman parte de grupos con sede en Estados Unidos: si algún dato se trata desde fuera del Espacio Económico Europeo, se hace con las garantías que prevé el RGPD, como las cláusulas contractuales tipo de la Comisión Europea o el Marco de Privacidad de Datos UE-EE. UU.

# 5. Cuánto tiempo los conservamos

- **Mientras seas abonado**, conservamos los datos necesarios para tu relación con el club.
- **Después de la baja**, el club conserva tus datos **6 años**, el plazo durante el cual debe guardar la documentación contable (art. 30 del Código de Comercio) y puede tener que atender reclamaciones. Pasado ese plazo, la plataforma los seudonimiza automáticamente, también los de tu cuenta de acceso: el nombre, los datos de contacto, el documento de identidad, el IBAN y el resto de datos que te identifican se borran o se sustituyen por un identificador, y solo se conservan, sin esos datos, los registros que necesita la contabilidad del club (recibos, importes y fechas).
- **Si pides la supresión** después de la baja, el club la hace de la misma manera, pasados al menos 30 días desde la baja y cuando no quedan recibos ni pagos pendientes.
- **Si el club rechaza una solicitud de alta**, los datos de la solicitud se seudonimizan 1 año después del rechazo.
- **El registro de cambios** se conserva 6 años, y **los ficheros de las remesas** enviadas al banco, 6 años desde su fecha.
- **Los eventos de seguridad** se conservan 90 días.
- **Los paquetes de datos que descargues** están disponibles 7 días y después se borran.
- **Las copias de seguridad**, cifradas, se conservan hasta 30 días y no se modifican: los datos suprimidos desaparecen de ellas cuando las copias caducan.

# 6. Tus derechos

Puedes ejercer en cualquier momento los derechos de **acceso, rectificación, supresión, oposición, limitación del tratamiento y portabilidad**, y **retirar los consentimientos** que hayas dado:

- En la app puedes consultar tus datos, corregir los de contacto y la dirección, y gestionar la foto, las notas y los documentos de tus perros.
- Desde el perfil de la app puedes descargar un paquete con tus datos («Descargar mis datos»).
- En la página «Tu cuenta» de este sitio puedes cambiar el nombre, el idioma y la contraseña, y cerrar las sesiones abiertas.
- Para cualquier otra petición, escribe a [info@agilitycanic.cat](mailto:info@agilitycanic.cat). Si es necesario, el club puede pedirte que acredites tu identidad. Te responderemos en el plazo de un mes.

La supresión se hace una vez tramitada la baja del club (apartado 5). Si consideras que no se han atendido correctamente tus derechos, puedes presentar una reclamación ante la **Agencia Española de Protección de Datos** ([www.aepd.es](https://www.aepd.es)).

# 7. Seguridad

La plataforma solo funciona con conexiones cifradas (HTTPS). Las contraseñas se guardan con un algoritmo de resumen robusto y nunca en claro; cuando eliges una, comprobamos que no aparezca en filtraciones conocidas enviando al servicio público Have I Been Pwned solo los cinco primeros caracteres de su resumen, nunca la contraseña. Los IBAN se guardan cifrados, los enlaces de acceso por correo son de un solo uso y caducan, el acceso se bloquea temporalmente tras demasiados intentos fallidos, puedes cerrar las sesiones abiertas desde tu cuenta, los cambios que hace el equipo del club quedan registrados y las copias de seguridad se cifran.

# 8. Menores

Si el abonado es menor de 14 años, sus datos los facilita y sus derechos los ejerce el padre, la madre o el tutor legal, y el club recoge su autorización.

# 9. Cookies y almacenamiento en el navegador

La plataforma solo utiliza cookies y almacenamiento local técnicos, necesarios para que funcione: mantener la sesión iniciada y recordar el idioma y algunas preferencias de pantalla. No utiliza cookies publicitarias ni de analítica, y por eso no te pide ningún consentimiento para cookies.

# 10. Cambios en esta política

Si esta política cambia, la plataforma te pedirá que leas y aceptes la nueva versión al entrar; la versión que aceptes y la fecha quedan registradas en tu cuenta.

## English

This policy explains how your personal data are processed on the management platform the club uses: the app for members, the club's administration and your sign-in account.

# 1. Who the controller is

- **Controller:** Club Agility Cànic
- **Tax ID (NIF):** G63189617
- **Address:** Carrer Sant Pere, 10, 08392 Sant Andreu de Llavaneres
- **Privacy contact:** [info@agilitycanic.cat](mailto:info@agilitycanic.cat)

For now the platform serves only this club, which is the controller of all the data processed on it: the data of your sign-in account and the data of your relationship with the club. AgilityHub is the technology provider that develops and runs the platform, and it processes the data only on the club's behalf.

# 2. What data we process

- **Sign-in account:** email address, name, language, password if you choose one (only an irreversible hash is kept), your profiles at the club (student, instructor or administrator) and the devices where you are signed in, with the date each was last used.
- **Member record:** first name and surnames, identity document, date of birth, gender, contact emails and phone numbers, postal address, member number, plan and rate, joining and leaving dates, family group and the club's remarks.
- **Dogs:** name, sex, breed, date of birth, microchip number, photo, vaccination record and other documents, level and its history, licences, the notes you leave for the instructors and the instructors' observations.
- **Activity at the club:** class and training bookings, waiting lists, attendance, activity registrations, packs, inactivity periods, leave requests and the instructors' follow-up.
- **Payments:** receipts and collections. For direct debit, the IBAN (stored encrypted), the account holder and their tax ID, and the SEPA mandate reference and date. For card payments, your customer identifier at Stripe, the card brand and the last four digits of the card.
- **Consents:** your acceptance of the privacy policy and your authorisation for the use of images, with the version and date; those you give on the sign-up form also with the language and an irreversible fingerprint of the IP address they were given from.
- **Communications:** the notices you receive (in the app, by email, by SMS or as a notification on your device), their delivery status, your notice preferences and, if you turn on notifications on a device, the subscription address your browser provides.
- **Security and change log:** failed sign-in attempts and other security events, with the IP address and browser, and the log of the changes made to the data, both by the club's team and by you (who, what and when, with the IP address and browser).

You provide these data (when you sign up, in the app or at the club) or the club's team enters them; the data the club held in the management software it used before are transferred to this platform. Technical data (sessions, notices and security records) are generated as you use it. The fields marked as required on the sign-up form are needed to manage your membership: without them the club cannot process your sign-up.

# 3. Why we use them and on what legal basis

- **Managing your relationship with the club** (sign-up, plan, classes, bookings, training, activities, attendance, dog levels, inactivity and leave) and **giving you access to the platform**: performance of the membership contract (Art. 6(1)(b) GDPR).
- **Collecting fees and payments** by direct debit, card or cash, and keeping the club's accounts: performance of the contract and compliance with accounting and tax obligations (Art. 6(1)(b) and 6(1)(c)).
- **Sending you service notices** (bookings, class changes and cancellations, places that become free, receipts and sign-in links): performance of the contract.
- **Sending you the club's announcements**: the club's legitimate interest in keeping its members informed (Art. 6(1)(f)). You can stop receiving them by email from your profile in the app or from the link in each email.
- **Sending notifications to your device**: only if you turn them on and allow them in your browser. You can turn them off at any time.
- **Recording whether you authorise the club to publish photos and videos showing you or your dog**: your consent (Art. 6(1)(a)), which you can withdraw at any time.
- **Protecting the platform and the accounts** (detecting improper access, a temporary lock after too many failed attempts, the change log): the club's legitimate interest in the security of the data (Art. 6(1)(f)).
- **Handling your rights and requests from the authorities**: compliance with legal obligations (Art. 6(1)(c)).

We do not build commercial profiles or sell your data. The platform's automatic processes only apply the club's rules, such as a pack expiring or a class being cancelled without enough participants.

# 4. Who has access and to whom we disclose them

- **The club's team:** the people who run the club see the data they need to manage it, and instructors the data they need to teach the classes (for example, the members and dogs in the classes, their levels and attendance).
- **AgilityHub**, the platform's technology provider, as processor (Art. 28 GDPR): it processes the data only to provide the service to the club and give it technical support. When its team has to enter the club's administration to solve an incident, it does so with temporary access that is logged.
- **DigitalOcean**, processor: hosting of the servers, database, files and backups, in data centres in the European Union.
- **Brevo**, processor: sending emails and SMS messages.
- **Stripe**, processor: card payments, including recurring charges to a card you leave saved. Card details are entered on Stripe's pages: the platform never receives the full card number.
- **The club's bank**, which receives the direct debit batches (account holder, IBAN, mandate reference and amount) to collect them.
- **Public authorities**, when the law requires it.

If you turn on notifications on a device, the message reaches it through the notification service of your browser or operating system vendor (for example Google, Apple, Mozilla or Microsoft), encrypted so that this service cannot read its content.

The data are hosted in the European Union. DigitalOcean and Stripe belong to groups based in the United States: if any data are processed from outside the European Economic Area, this is done with the safeguards the GDPR provides, such as the European Commission's standard contractual clauses or the EU-U.S. Data Privacy Framework.

# 5. How long we keep them

- **While you are a member**, we keep the data needed for your relationship with the club.
- **After you leave**, the club keeps your data for **6 years**, the period during which it must keep its accounting records (Article 30 of the Spanish Commercial Code) and may have to deal with claims. After that period, the platform pseudonymises them automatically, your sign-in account included: your name, contact details, identity document, IBAN and the other data that identify you are deleted or replaced by an identifier, and only the records the club's accounts need (receipts, amounts and dates) are kept, without those data.
- **If you ask for erasure** after leaving, the club carries it out in the same way, at least 30 days after you left and once no receipts or payments are pending.
- **If the club rejects a sign-up request**, the request's data are pseudonymised 1 year after the rejection.
- **The change log** is kept for 6 years, and **the direct debit files** sent to the bank for 6 years from their date.
- **Security events** are kept for 90 days.
- **Data packages you download** are available for 7 days and then deleted.
- **Backups**, encrypted, are kept for up to 30 days and are not modified: erased data disappear from them when the backups expire.

# 6. Your rights

You can exercise at any time your rights of **access, rectification, erasure, objection, restriction of processing and portability**, and **withdraw any consent** you have given:

- In the app you can see your data, correct your contact details and address, and manage your dogs' photos, notes and documents.
- From your profile in the app you can download a package with your data ("Download my data").
- On the "Your account" page of this site you can change your name, language and password, and close open sessions.
- For any other request, write to [info@agilitycanic.cat](mailto:info@agilitycanic.cat). If necessary, the club may ask you to prove your identity. We will reply within one month.

Erasure is carried out once you have left the club (section 5). If you think your rights have not been properly handled, you can lodge a complaint with the **Spanish Data Protection Agency** (Agencia Española de Protección de Datos, [www.aepd.es](https://www.aepd.es)).

# 7. Security

The platform works only over encrypted connections (HTTPS). Passwords are stored with a strong hashing algorithm and never in plain text; when you choose one, we check that it does not appear in known data breaches by sending the public Have I Been Pwned service only the first five characters of its hash, never the password. IBANs are stored encrypted, sign-in links sent by email can be used only once and expire, access is temporarily locked after too many failed attempts, you can close open sessions from your account, the changes the club's team makes are logged and backups are encrypted.

# 8. Children

If the member is under 14, their data are provided and their rights exercised by their parent or legal guardian, and the club collects the guardian's authorisation.

# 9. Cookies and browser storage

The platform uses only technical cookies and local storage needed for it to work: keeping you signed in and remembering your language and some display preferences. It uses no advertising or analytics cookies, which is why it does not ask for your consent to cookies.

# 10. Changes to this policy

If this policy changes, the platform will ask you to read and accept the new version when you sign in; the version you accept and the date are recorded in your account.
