# E7-T03 round 2 (review #4): a fictional but valid P-256 public key for the static route fixture (e7-routes.json).
# Computes k·G on P-256 in pure Python (no dependency) and prints the uncompressed point 04‖x‖y in base64url, checks that it
# is on the curve, and prints a 16-byte auth secret derived from the same seed. The Java tests generate their keys at run time.
# The static fixtures keep round 1's low-entropy 16-byte `auth` (0x22 × 16): a random one under an "auth" key would look like
# a credential to the CI secret scan (gitleaks' generic-api-key rule).
import base64, hashlib, sys

P = 2**256 - 2**224 + 2**192 + 2**96 - 1
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)

def add(p1, p2):
    if p1 is None: return p2
    if p2 is None: return p1
    (x1, y1), (x2, y2) = p1, p2
    if x1 == x2 and (y1 + y2) % P == 0: return None
    if p1 == p2: m = (3 * x1 * x1 + A) * pow(2 * y1, -1, P) % P
    else: m = (y2 - y1) * pow(x2 - x1, -1, P) % P
    x3 = (m * m - x1 - x2) % P
    return (x3, (m * (x1 - x3) - y1) % P)

def mul(k, point):
    result = None
    while k:
        if k & 1: result = add(result, point)
        point = add(point, point); k >>= 1
    return result

def b64(data): return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

seed = sys.argv[1] if len(sys.argv) > 1 else 'agilityhub-e7-routes-fixture'
k = int.from_bytes(hashlib.sha256(seed.encode()).digest(), 'big') % N
x, y = mul(k, G)
assert (y * y - (x ** 3 + A * x + B)) % P == 0
print('p256dh', b64(b'\x04' + x.to_bytes(32, 'big') + y.to_bytes(32, 'big')))
print('auth', b64(hashlib.sha256((seed + ':auth').encode()).digest()[:16]))
