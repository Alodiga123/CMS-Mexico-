# -*- coding: utf-8 -*-
"""Identity block for the e2e scripts: a valid CURP and RFC (structure, check digits, consistent
with the name and birth date) plus the document and contact fields the KYC asks for. Import it
from any verify_*.py: {**identity("Onus 123"), "phoneNumber": ...}. The CURP is derived from the
name and a sequence so each test customer is unique."""
import unicodedata, hashlib, datetime, time, os, zlib, struct, base64

# stable within one script run (a script may derive the same identity twice), different between runs, so
# a customer left behind by an interrupted run never collides by CURP with the next run
_SALT = "%d-%d" % (os.getpid(), time.time_ns())

CURP_ALPHABET = "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"
RFC_ALPHABET = "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ"
VOWELS = "AEIOU"


def normalize(s):
    n = unicodedata.normalize("NFD", s or "")
    n = "".join(ch for ch in n if unicodedata.category(ch) != "Mn").upper()
    return " ".join("".join(ch if ch.isalnum() or ch == " " else " " for ch in n).split())


def curp_digit(first17):
    total = sum(CURP_ALPHABET.index(ch) * (18 - i) for i, ch in enumerate(first17))
    return str((10 - total % 10) % 10)


def rfc_digit(first12):
    total = sum(max(RFC_ALPHABET.find(ch), 0) * (13 - i) for i, ch in enumerate(first12))
    rem = total % 11
    return "0" if rem == 0 else "A" if rem == 1 else str(11 - rem)


def _inner_vowel(word):
    for ch in word[1:]:
        if ch in VOWELS: return ch
    return "X"


def _consonant(word, skip_first=True):
    for ch in (word[1:] if skip_first else word):
        if ch.isalpha() and ch not in VOWELS: return ch
    return "X"


def png_bytes(width=64, height=40, rgb=(30, 90, 160)):
    """A small valid PNG (solid colour) to stand in for the scanned identification in test benches."""
    filt = bytes([0])
    raw = b"".join(filt + bytes(rgb) * width for _ in range(height))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    signature = bytes([137, 80, 78, 71, 13, 10, 26, 10])
    return signature + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def document_image(file_name="ine_frente.png"):
    """Inline file for documentImage / POST .../kyc/documents (simulated verifier: *_mal.* = otra persona, *_ilegible.* = ilegible)."""
    return {"fileName": file_name, "contentType": "image/png", "base64": base64.b64encode(png_bytes()).decode()}


def identity(full_name, seq=None, birth=datetime.date(1990, 5, 14), sex="M", state="DF", pep=False, document=True):
    """full_name: 'Nombre Apellido' or 'Nombre Segundo ApellidoP ApellidoM' (words without letters,
    like the run number the scripts add, count as the surname PRUEBA). Returns the KYC fields."""
    words = normalize(full_name).split() or ["PRUEBA"]
    alpha = [w for w in words if any(ch.isalpha() for ch in w)]
    fw = alpha[0] if alpha else "PRUEBA"
    pat = alpha[1] if len(alpha) > 1 else "PRUEBA"
    mat = alpha[2] if len(alpha) > 2 else "X"
    key = seq if seq is not None else int(hashlib.sha1((full_name + "|" + _SALT).encode()).hexdigest(), 16) % 100000
    # birth date and homonymy differentiator vary with the key so test customers never share a CURP
    day = 1 + key % 28; month = 1 + (key // 28) % 12
    birth = datetime.date(birth.year - (key // 336) % 20, month, day)
    yymmdd = birth.strftime("%y%m%d")
    head = (pat[0] + _inner_vowel(pat) + (mat[0] if mat != "X" else "X") + fw[0]).replace("Ñ", "X")
    diff = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[(key // 6720) % 36] if birth.year < 2000 else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[(key // 6720) % 26]
    curp17 = head + yymmdd + sex + state + _consonant(pat) + (_consonant(mat) if mat != "X" else "X") + _consonant(fw) + diff
    curp = curp17 + curp_digit(curp17)
    rfc12 = head + yymmdd + ("%02d" % (key % 100))
    rfc = rfc12 + rfc_digit(rfc12)
    return {
        "firstNames": fw.title(), "paternalSurname": pat.title(), "maternalSurname": mat.title() if mat != "X" else "",
        "birthDate": birth.isoformat(), "sex": sex, "curp": curp, "rfc": rfc,
        "email": (fw + "." + pat + str(key) + "@prueba.finsus.mx").lower(),
        "nationality": "MX", "occupation": "Empleado", "addressLine": "Av. Prueba 100", "postalCode": "06600", "state": "CDMX",
        "documentType": "INE", "documentNumber": "IDMEX%09d" % (key * 7 % 10 ** 9), "documentExpiresAt": (datetime.date.today() + datetime.timedelta(days=900)).isoformat(),
        "pep": pep,
        **({"documentImage": document_image()} if document else {}),
    }


if __name__ == "__main__":
    import json
    print(json.dumps(identity("Onus 123"), indent=1))
