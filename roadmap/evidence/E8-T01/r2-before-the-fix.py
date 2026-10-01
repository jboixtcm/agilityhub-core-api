#!/usr/bin/env python3
"""E8-T01 round 2: revert each round-2 fix in place (compile-safe), so the new tests can be seen failing; restore byte for byte.

Usage: python3 roadmap/evidence/E8-T01/r2-before-the-fix.py revert|restore|check
Backups and checksums live in target/r2-backup/ (never committed). Read-only git only (`git show HEAD:<path>`).
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BACKUP = ROOT / 'target/r2-backup'

# Point → (file, old text, text before the fix). Each `old` must occur exactly once.
EDITS = [
    ('1 checkout extensions: no guard before the stub', 'src/main/java/com/agilityhub/core/payments/api/CheckoutController.java',
     """        if (request.extended()) {
            checkout.authorize(request.memberId(), request.signupToken());
            access.checkoutReferences(request.memberId(), request.bookingId(), request.upfrontPaymentIds());
            throw new UnsupportedOperationException();
        }""",
     """        if (request.extended()) { throw new UnsupportedOperationException(); }"""),
    ('1 stubs: a dog of any member', 'src/main/java/com/agilityhub/core/payments/application/BillingContractAccess.java',
     """        if (!dogs.ownerOf(dogId).map(memberId::equals).orElse(false)) { throw notFound(); }""",
     """        dog(dogId);"""),
    ('1 stubs: /me billing without the caller', 'src/main/java/com/agilityhub/core/payments/api/MyBillingController.java',
     """        access.mutableMember(access.me());""", """        access.tenant();"""),
    ('1 stubs: /me packs without the caller', 'src/main/java/com/agilityhub/core/payments/api/MyBillingController.java',
     """        access.member(access.me());""", """        access.tenant();"""),
    ('1 stubs: /me lifecycle reads without the caller', 'src/main/java/com/agilityhub/core/clubs/census/application/LifecycleContractAccess.java',
     """    public void me() { member(callerMember()); }""", """    public void me() { tenant(); }"""),
    ('1 stubs: /me lifecycle writes without the caller', 'src/main/java/com/agilityhub/core/clubs/census/application/LifecycleContractAccess.java',
     """    public void mutableMe() { mutableMember(callerMember()); }""", """    public void mutableMe() { tenant(); }"""),
    ('2 webhook: club first, then only a missing signature refused', 'src/main/java/com/agilityhub/core/payments/api/StripeWebhookController.java',
     """        signatures.authenticate(clubId, signature, body);
        access.stripeClub(clubId);""",
     """        access.stripeClub(clubId);
        if (signature == null || signature.isBlank()) { signatures.authenticate(clubId, null, body); }"""),
    ('4 PATCH: fromMonth null accepted', 'src/main/java/com/agilityhub/core/clubs/census/api/LifecycleRequests.java',
     """            if (value == null) { throw new IllegalArgumentException("fromMonth is never null"); }
""", ""),
    ('4 PATCH: unknown fields ignored', 'src/main/java/com/agilityhub/core/clubs/census/api/LifecycleRequests.java',
     """        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }""",
     """        @JsonAnySetter public void rejectUnknown(String name, Object value) { }"""),
    ('5 bank key: MIGRATION_BANK_KEY read again', 'src/main/resources/application.yml',
     """  billing:
    # Rulings E43/E85""",
     """  migration:
    bank-key: ${MIGRATION_BANK_KEY:}
  billing:
    # Rulings E43/E85"""),
    ('5 bank key: the migration encrypts with its own key', 'src/main/java/com/agilityhub/core/migration/application/MigrationBankVault.java',
     """    public MigrationBankVault(BankAccountVault vault) { this.vault=vault; }
    /** An apply with bank data needs `BILLING_BANK_KEY`: without a usable key the run stops before writing anything. */
    public void requireKey() { if (!vault.configured()) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); } }
    public String encrypt(String iban,String clubId,String memberId) { return vault.encrypt(iban,clubId,memberId); }""",
     """    @org.springframework.beans.factory.annotation.Value("${core.migration.bank-key:}") private String legacy;
    public MigrationBankVault(BankAccountVault vault) { this.vault=vault; }
    private BankAccountVault legacy() { return new BankAccountVault(legacy); }
    public void requireKey() { if (!legacy().configured()) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); } }
    public String encrypt(String iban,String clubId,String memberId) { return legacy().encrypt(iban,clubId,memberId); }"""),
]
# Points 3, 4 (published schemas), 7 and 8: the files as HEAD (round 1) has them.
FROM_HEAD = [
    ('3+4 the published snapshot without version nor nullable ends', 'docs/openapi/openapi.json'),
    ('3 the S13 §6 example without version', 'src/test/resources/fixtures/contracts/e8-me-inactivity-periods.json'),
    ('7 the catalog main rows of round 1', 'docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md'),
    ('8 the leave reasons of round 1', 'src/main/resources/parameters/catalog.yaml'),
]


def files():
    return sorted({edit[1] for edit in EDITS} | {item[1] for item in FROM_HEAD})


def digest(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def revert():
    if (BACKUP / 'checksums.json').exists():
        sys.exit('already reverted: run restore first')
    BACKUP.mkdir(parents=True, exist_ok=True)
    checksums = {}
    for path in files():
        (BACKUP / path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, BACKUP / path)
        checksums[path] = digest(path)
    (BACKUP / 'checksums.json').write_text(json.dumps(checksums, indent=1))
    for label, path, old, before in EDITS:
        text = (ROOT / path).read_text()
        if text.count(old) != 1:
            restore()
            sys.exit(f'{label}: the fixed text is not in {path}')
        (ROOT / path).write_text(text.replace(old, before))
        print('reverted', label)
    for label, path in FROM_HEAD:
        content = subprocess.run(['git', 'show', f'HEAD:{path}'], cwd=ROOT, check=True, capture_output=True).stdout
        (ROOT / path).write_bytes(content)
        print('reverted', label)


def restore():
    for path in files():
        shutil.copyfile(BACKUP / path, ROOT / path)
    check()
    (BACKUP / 'checksums.json').unlink(missing_ok=True)


def check():
    checksums = json.loads((BACKUP / 'checksums.json').read_text())
    for path, expected in checksums.items():
        actual = digest(path)
        print(('restored ' if actual == expected else 'DIFFERS  ') + path)
        if actual != expected:
            sys.exit(1)


if __name__ == '__main__':
    {'revert': revert, 'restore': restore, 'check': check}[sys.argv[1]]()
