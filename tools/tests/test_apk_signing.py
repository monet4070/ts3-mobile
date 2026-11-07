import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from apk_signing import apk_certificate_sha256


class ApkSigningOutputTest(unittest.TestCase):
    fingerprint = "a2" * 32

    def test_legacy_numbered_signer_is_supported(self):
        self.assertEqual(self.fingerprint, apk_certificate_sha256(
            "Signer #1 certificate SHA-256 digest: " + self.fingerprint.upper() + "\n"))

    def test_current_v2_signer_is_supported(self):
        self.assertEqual(self.fingerprint, apk_certificate_sha256(
            "V2 Signer: certificate DN: CN=Android Debug\nV2 Signer: certificate SHA-256 digest: " + self.fingerprint + "\n"))

    def test_matching_scheme_reports_do_not_look_like_multiple_certificates(self):
        report = "\n".join(scheme + " Signer: certificate SHA-256 digest: " + self.fingerprint for scheme in ["V1", "V2", "V3.1"])
        self.assertEqual(self.fingerprint, apk_certificate_sha256(report))

    def test_distinct_certificates_or_a_second_numbered_signer_are_rejected(self):
        for report in [
            "Signer #2 certificate SHA-256 digest: " + self.fingerprint,
            "V2 Signer: certificate SHA-256 digest: " + self.fingerprint + "\nV3 Signer: certificate SHA-256 digest: " + "b3" * 32,
        ]:
            with self.assertRaises(RuntimeError):
                apk_certificate_sha256(report)

    def test_source_stamp_is_not_accepted_as_the_apk_signing_certificate(self):
        with self.assertRaises(RuntimeError):
            apk_certificate_sha256("Source Stamp Signer: certificate SHA-256 digest: " + self.fingerprint)

    def test_missing_and_malformed_fingerprints_are_rejected(self):
        for report in ["", "V2 Signer: certificate SHA-256 digest: a2", "Signer #1 certificate SHA-256 digest: " + "z" * 64]:
            with self.assertRaises(RuntimeError):
                apk_certificate_sha256(report)


if __name__ == "__main__":
    unittest.main()
