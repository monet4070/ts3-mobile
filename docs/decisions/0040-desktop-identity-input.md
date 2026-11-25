# ADR 0040: Bound desktop identity import before the vault

Status: accepted for development preview; official desktop compatibility and public entry remain gated.

## Decision

- Desktop TS3 INIs are not `.ts3mbak` files. Use distinct picker sources, the same system picker slot, and the same private candidate, comparison, revision and confirmation transaction ([ADR 0031](0031-confirmed-identity-restore.md)). The desktop source has no backup passphrase.
- Count actual bytes through the selected provider, stop after 64 KiB plus one byte, close the stream before publishing a candidate, and do not rely on MIME, extension or provider metadata. Never persist the grant or write a plaintext temporary file.
- Decode strict UTF-8, with an optional initial UTF-8 BOM and LF/CRLF. Accept one `[Identity]` section and one quoted `identity` key. Drop known display fields; reject extra sections, unsupported keys, external references or duplicate identity values. Pass only a canonical identity-only INI to the pinned identity validator; do not reimplement its cryptography.
- Clear mutable input and candidate buffers when no longer needed. JVM implementation and library strings are not guaranteed erasable. Public observable state carries summaries and fixed failure kinds, no source details or secret.
- Desktop import of the same UID and offset is a no-op, including when refreshing after an outdated revision. A lower level for the same UID requires a separate fresh acknowledgement. Use existing encrypted storage, transactional rollback and connection leases. Ordinary encrypted backup restore is unchanged.
- Debug enables the preview. Standard Release does not. QA may exercise the actual minified code with `-PdesktopIdentityImportExperiment=true`; publishing the entry requires a verified official Windows export and any other claimed platform's export, plus release and permission/rollback evidence.

## Consequences

The importer deliberately will not handle multi-identity files, inferred encodings, arbitrary INI extensions or changing nickname settings. A synthesized vector proves the parser and protocol checks, not official client interoperability. Do not request a user's personal identity or commit an exported private key.
