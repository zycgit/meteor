# Public TLS acceptance test material

**TEST ONLY. All private keys and the password `meteor-test` are intentionally public.**
Never install these CAs in a system trust store or use any of these keys in production.
The two independent CAs and all leaf certificates are valid from 2020-01-01 00:00:00 UTC
through 2045-01-01 00:00:00 UTC, so generation does not depend on the host clock.

| File | Contents and intended role |
| --- | --- |
| `server.p12` | Private key alias `server`; trusted CA chain; serverAuth; SAN DNS:localhost, DNS:*.meteor.test, DNS:runner.meteor.test, IP:127.0.0.1, IP:::1 |
| `client.p12` | Private key alias `client`; trusted CA chain; clientAuth |
| `untrusted-client.p12` | Private key alias `untrusted-client`; other CA chain; clientAuth |
| `wrong-host-server.p12` | Private key alias `wrong-host-server`; trusted CA chain; serverAuth; SAN DNS:other.invalid |
| `trust.p12` | Exactly one trustedCertEntry, alias `meteor-test-ca`; trusted CA only; no private key |
| `untrusted-trust.p12` | Exactly one trustedCertEntry, alias `meteor-untrusted-test-ca`; other CA only; no private key |
| `ca.pem`, `untrusted-ca.pem` | The corresponding CA certificates, without CA private keys |
| `*.pem`, `*-key.pem` | Each leaf certificate and its unencrypted PKCS#8 private key, for official TLS peers |

Every PKCS#12 store and key password is `meteor-test`. Leaf PEM files contain one certificate;
the matching `.p12` contains the leaf and its CA chain. The two CA private keys are discarded.
The wrong-host certificate has only DNS:other.invalid in its SAN, preserving hostname rejection.

`server-chain.pem` contains the server certificate followed by its CA. The same `ca.pem`
is used by all PEM clients. Java applications load this directory as `/certs/acceptance/`;
other language peers use the same files from `meteor-test/resource/certs/acceptance`.

These committed files are sufficient for normal tests. Certificate maintenance does not
run during startup, and no certificate tool is required on the host.
