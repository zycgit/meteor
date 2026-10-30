Local multi-protocol demonstration certificates

server.p12: localhost / 127.0.0.1 server identity.
client.p12: client identity for mutual TLS.
trust.p12: certificate authority trusted by the demonstration peers.

All three fixture stores use the password meteor-test. These public test keys
are only for automated composition scenarios. This set remains separate from
acceptance material because its certificate identities and CA differ.
