# ts3j protocol library — keep the entire package; ts3j reflects on its own
# command/event/identity classes and is not annotation-driven, so a broad
# keep is the safest minimum.
-keep class com.github.manevolent.ts3j.** { *; }

# ts3j's vendored NaCl/Ed25519 implementation (Punisher package) is reached
# only through the identity classes above; keep it explicitly so R8 does not
# prune the crypto primitives the identity loader depends on.
-keep class Punisher.** { *; }

# BouncyCastle — ts3j's LocalIdentity/Identity/Ts3Crypt use the BC crypto,
# math/ec, asn1, jce, and jce/provider packages directly. Keep the classes
# ts3j references by name so R8 does not rename or inline them away.
-keep class org.bouncycastle.math.ec.** { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-keep class org.bouncycastle.crypto.generators.** { *; }
-keep class org.bouncycastle.crypto.digests.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.crypto.modes.** { *; }
-keep class org.bouncycastle.crypto.signers.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jce.spec.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**

# dnsjava — ts3j uses SRV-record lookups (org.xbill.DNS) for server discovery.
# Keep the resolver entry points ts3j calls by name.
-keep class org.xbill.DNS.Lookup { *; }
-keep class org.xbill.DNS.Name { *; }
-keep class org.xbill.DNS.Record { *; }
-keep class org.xbill.DNS.SRVRecord { *; }
-dontwarn org.xbill.DNS.**

# ini4j — ts3j loads INI-format identity/config via org.ini4j.Ini.
-keep class org.ini4j.Ini { *; }
-dontwarn org.ini4j.**

