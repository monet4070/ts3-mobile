# ADR-0028: compatible local Keystore envelopes

Date: 2026-11-07. Status: implemented and offline/emulator compatibility validated.

Extract only the existing AES-GCM v1 envelope and explicit local-key access.
Do not change serialization, transactions, aliases, storage names, fields,
permissions or ciphertext layout. Portable C1 backup crypto remains separate:
it uses password derivation/AAD and is not a device-local v1 envelope.

| Vault | DataStore | Field | Keystore alias |
| --- | --- | --- | --- |
| Identity | ts3_identity | encrypted_identity | ts3_mobile_identity_v1 |
| Form | ts3_connection_settings | encrypted_form | ts3_mobile_connection_settings_v1 |
| Bookmarks | ts3_bookmarks | encrypted_bookmarks | ts3_mobile_bookmarks_v1 |

All retain `v1:base64(iv):base64(ciphertext+128-bit-tag)`, standard padded Base64
without wrapping on writes, AES/GCM/NoPadding, no AAD and the provider-generated
random IV. Each Vault keeps its own serializer, upper bounds and mutation/error
rules. No bulk re-encryption or new storage migration is performed.

Reads of existing ciphertext call findExistingKey ONLY. If missing or unusable,
fail explicitly without creating/replacing an alias, clearing the blob or
returning a default identity/form/bookmark list. New explicit writes may use
getOrCreateKeyForNewData. An alias containing a non-secret/unusable key is an
error, never a reason to overwrite it. No key or plaintext enters error text.

Keep the public Context constructors and getOrCreate/read/write methods. Keep
BookmarkVault(Context, DataStore=default), including its Kotlin default-call
constructor shape used by the independent upgrade probe. Internal store/cipher
constructors enable scratch DataStores and test-only aliases without deleting
production keys/files. C0 deliberately leaves identity creation/transaction
ownership to C2; extracting crypto must not silently change that behavior.

The small shared envelope is pure JCA/JVM; AndroidKeystoreKeys alone owns Android
key lookup/generation. It has no recorded global mutable state, key cache or
fallback provider. Existing DataStore actors retain write serialization. Large
coordinator/Activity/service files receive no changes in this stage.

Prove old-write/new-read and new-write/old-read for identity INI, form binary and
bookmark binary codecs, then through each real scratch Vault/DataStore with
actual Android Keystore test aliases. Cover wrong key, corrupted tag, unknown
version, missing key, alias type mismatch, no rewrite, and bookmark revision
transactions. Upgrade a fresh synthetic old install through the unchanged default
constructors/aliases. Test-signed/minified evidence must distinguish raw key/blob
preservation from actual optimized Vault UI entry points.

See [C0 evidence](../testing/IMPROVEMENT_C0_2026-11-07_ZH.md): app299 JVM,
188 passing Android cases plus one default-skipped external fixture, real three-
Vault bidirectional tests and unchanged default-alias in-place upgrade. C2 owns
identity creation/replacement concurrency; no such policy was hidden in C0.
