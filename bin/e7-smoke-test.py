#!/usr/bin/env python3
"""E7-T07 step 1 (review #1 of E7-T04 round 3): bin/e7-smoke's feed expectation follows the SMS delivery's status, in both of
its modes, without real credentials. The two recorded answers are the N-08a card of `GET /me/notifications` and the notice's
`GET /notifications/{id}`, in the API's shape (fictional data):

- the log sender (no Twilio credentials): the SMS is SENT and the card lists APP and SMS;
- Twilio credentials with the allow-list (`SMS_ALLOWED_NUMBERS` = `SMOKE_SMS_TO`): the member's fictional number is
  SKIPPED_NOT_ALLOWED and the card lists APP only.

Before the fix, the smoke required SMS in the card whatever the delivery, so the second answer failed the run before its
real-SMS step.
"""
import copy
import runpy
import unittest
from pathlib import Path

smoke = runpy.run_path(str(Path(__file__).with_name('e7-smoke')))
E7 = smoke['E7']
feed_card_problem = smoke['feed_card_problem']

NOTICE = 'e7t07-n08a-member'


def card(channels):
    """`GET /me/notifications` item (MeNotification) of the N-08a notice."""
    return {'id': NOTICE, 'code': 'N-08a', 'category': 'CLUB_CHANGES', 'icon': 'x', 'color': 'ERROR',
            'title': 'Clase cancelada por el club', 'body': 'Texto ficticio del aviso.', 'createdAt': '2026-10-05T06:31:12.204Z',
            'channels': channels, 'readAt': None,
            'action': {'type': 'CHANGE_CLASS', 'params': {'dogId': 'e7t07-dog'}, 'enabled': True}}


def delivery(channel, target, status, attempts=0, provider_ref=None, last_error=None, sent_at=None):
    """`GET /notifications/{id}` delivery (DeliveryView)."""
    return {'channel': channel, 'target': target, 'status': status, 'attempts': attempts, 'nextAttemptAt': None,
            'providerRef': provider_ref, 'lastError': last_error, 'sentAt': sent_at, 'deliveredAt': None, 'failedAt': None}


def detail(sms):
    """`GET /notifications/{id}` (NotificationDetail) of the same notice: APP, the e-mail of the local mailbox and `sms`."""
    deliveries = [delivery('APP', None, 'DELIVERED'),
                  delivery('EMAIL', 'member.e7t07@example.test', 'SENT', 1, 'local-e7t07', None, '2026-10-05T06:31:12.611Z'), sms]
    channels = [{'channel': d['channel'], 'status': d['status']} for d in deliveries]
    return {'id': NOTICE, 'createdAt': '2026-10-05T06:31:12.204Z', 'code': 'N-08a', 'category': 'CLUB_CHANGES', 'audience': 'MEMBER',
            'recipient': {'displayName': 'Example Member', 'memberId': 'e7t07-member', 'email': None}, 'channels': channels, 'readAt': None,
            'title': 'Clase cancelada por el club', 'body': 'Texto ficticio del aviso.', 'smsBody': 'Texto SMS ficticio.', 'locale': 'es',
            'templateId': 'e7t07-template', 'templateVersion': 2, 'eventType': 'ClassCancelledByClub',
            'subject': {'dogId': 'e7t07-dog', 'bookingId': None, 'classSessionId': 'e7t07-class', 'waitlistEntryId': None,
                        'trainingBookingId': None, 'invoiceId': None, 'activityId': None, 'taskId': None, 'memberId': None},
            'deliveries': deliveries}


# The log sender: the SMS is accepted (SENT) with its `log-…` reference, and the feed lists it.
LOG_SENDER = (card(['APP', 'SMS']),
              detail(delivery('SMS', '+34600000005', 'SENT', 1, 'log-00000000-0000-4000-8000-000000000007', None, '2026-10-05T06:31:12.730Z')))
# Twilio credentials with the allow-list: the fictional number is refused by the guard, never attempted, and the feed leaves it out.
ALLOW_LIST = (card(['APP']), detail(delivery('SMS', '+34600000005', 'SKIPPED_NOT_ALLOWED', 0, None, 'Not in SMS_ALLOWED_NUMBERS')))


class Feed:
    """Just what `E7.feed` reads: the member's feed, read-all, /me/home, the notice's detail; it records like the smoke."""
    def __init__(self, answer, real_sms):
        self.card, self.detail_answer = copy.deepcopy(answer)
        self.real_sms = real_sms
        self.x = 'member-token'
        self.x_note = {'id': NOTICE}
        self.recorded = []

    def call(self, method, path, *args, **kwargs):
        if path.startswith('/api/v1/me/notifications?'):
            return {'items': [self.card], 'page': 0, 'size': 20, 'totalItems': 1, 'unreadCount': 1}
        if path == '/api/v1/me/notifications/read-all':
            return {'unreadCount': 0}
        if path == '/api/v1/me/home':
            return {'notifications': {'unreadCount': 0}}
        raise AssertionError('unexpected call ' + method + ' ' + path)

    def detail(self, notification_id):
        assert notification_id == NOTICE
        return self.detail_answer

    def require(self, condition, message):
        if not condition:
            raise AssertionError(message)

    def record(self, step, status, values=''):
        self.recorded.append((step, status, values))


class FeedExpectationTest(unittest.TestCase):
    def test_log_sender_card_carries_the_sms_badge(self):
        stub = Feed(LOG_SENDER, real_sms=False)
        E7.feed(stub)
        self.assertIn("channels ['APP', 'SMS'] (SMS delivery ['SENT'])", stub.recorded[0][2])

    def test_twilio_allow_list_card_leaves_the_skipped_sms_out(self):
        # The answer the review reproduced: before the fix this raised «The card: channels ['APP'] …».
        stub = Feed(ALLOW_LIST, real_sms=True)
        E7.feed(stub)
        self.assertIn("channels ['APP'] (SMS delivery ['SKIPPED_NOT_ALLOWED'])", stub.recorded[0][2])

    def test_the_card_must_agree_with_the_delivery_in_both_modes(self):
        # A sent SMS the card hides, or a skipped one it shows, is a failure whatever the mode.
        log_card, log_detail = copy.deepcopy(LOG_SENDER)
        allow_card, allow_detail = copy.deepcopy(ALLOW_LIST)
        self.assertIsNone(feed_card_problem(log_card, log_detail['deliveries'], real_sms=False))
        self.assertIsNone(feed_card_problem(allow_card, allow_detail['deliveries'], real_sms=True))
        self.assertIsNone(feed_card_problem(log_card, log_detail['deliveries'], real_sms=True))  # an allow-listed number is sent
        self.assertIn("channels ['APP'], expected ['APP', 'SMS']", feed_card_problem(allow_card, log_detail['deliveries'], real_sms=False))
        self.assertIn("channels ['APP', 'SMS'], expected ['APP']", feed_card_problem(log_card, allow_detail['deliveries'], real_sms=True))
        with self.assertRaisesRegex(AssertionError, "expected \\['APP'\\]"):
            E7.feed(Feed((log_card, allow_detail), real_sms=True))

    def test_the_log_sender_must_send_and_the_action_stays_change_class(self):
        allow_card, allow_detail = copy.deepcopy(ALLOW_LIST)
        self.assertIn('the log sender did not send the SMS', feed_card_problem(allow_card, allow_detail['deliveries'], real_sms=False))
        no_sms = [d for d in allow_detail['deliveries'] if d['channel'] != 'SMS']
        self.assertEqual(feed_card_problem(allow_card, no_sms, real_sms=True), 'the notice has no SMS delivery')
        log_card, log_detail = copy.deepcopy(LOG_SENDER)
        log_card['action'] = None
        self.assertIn('expected CHANGE_CLASS', feed_card_problem(log_card, log_detail['deliveries'], real_sms=False))

    def test_a_failure_never_prints_a_phone_number(self):
        allow_card, _ = copy.deepcopy(ALLOW_LIST)
        _, log_detail = copy.deepcopy(LOG_SENDER)
        self.assertNotIn('+34', feed_card_problem(allow_card, log_detail['deliveries'], real_sms=False))


if __name__ == '__main__':
    unittest.main()
