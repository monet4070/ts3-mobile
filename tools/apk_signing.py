"""Read one current APK signing certificate from verified apksigner output."""

import re

CERTIFICATE = re.compile(
    r"^(?:Signer #(?P<index>[0-9]+)|V(?:1|2|3(?:\.1)?|4) Signer):? "
    r"certificate SHA-256 digest: (?P<digest>[a-fA-F0-9]{64})$"
)


def apk_certificate_sha256(report):
    certificates = set()
    for line in report.splitlines():
        match = CERTIFICATE.fullmatch(line.strip())
        if match is None:
            continue
        if match["index"] not in (None, "1"):
            raise RuntimeError("Expected one current APK signing certificate")
        certificates.add(match["digest"].lower())
    # A scheme may be reported separately; every scheme must agree on the same certificate.
    if len(certificates) != 1:
        raise RuntimeError("Expected one current APK signing certificate")
    return certificates.pop()
