# Aharon Message Protocol v1

## Scope

Aharon Message Protocol (AMP/1) carries small packets through a near-ultrasonic 4-FSK acoustic link. It is designed for short encrypted text messages and local device pairing, not bulk file transfer.

## Physical layer

- PCM: 48 kHz, 16-bit, mono.
- Modulation: 4-FSK, two bits per symbol.
- Strict profile data tones: 20.2, 20.6, 21.0, 21.4 kHz.
- Strict profile wake tone: 21.8 kHz.
- Strict symbol duration: 12 ms.
- Wake tone: 180 ms followed by a 60 ms guard interval.
- Sync sequence: `0 1 2 3 3 2 1 0`.
- Compatible profile is explicitly opt-in because frequencies below 20 kHz can be audible to some people.

The strict profile is a design target, not a promise that every phone speaker or microphone can reproduce the band. Hardware calibration remains mandatory before production release.

## Frame

All integer fields are network byte order (big-endian).

| Field | Bytes |
|---|---:|
| Version | 1 |
| Type | 1 |
| Sender transport ID | 8 |
| Receiver transport ID | 8 |
| Message ID | 8 |
| Payload length | 2 |
| Payload | 0–1024 |
| CRC32 | 4 |

Transport IDs are the first 63 bits of SHA-256 over the device public identity key. Receiver ID `0` is broadcast and is used only for discovery/pairing.

## Packet types

- `PAIR_REQUEST`
- `PAIR_RESPONSE`
- `DATA`
- `ACK`
- `PING`
- `PONG`

## Pairing

A pairing payload contains the 128-bit device UUID, UTF-8 display name and X.509 encoded P-256 public key. Both devices derive an ECDH secret and show the same six-digit short authentication string. The user must compare the code on both screens before confirming the contact.

## Message encryption

- Key agreement: ECDH P-256.
- KDF: HKDF-SHA-256.
- Authenticated encryption: AES-256-GCM.
- Nonce: random 96-bit nonce per message.
- AAD: sender ID + receiver ID + message ID.

Private identity keys are generated inside Android Keystore with `PURPOSE_AGREE_KEY` and are not exported.

## Reliability

`DATA` is acknowledged by an `ACK` carrying the same message ID. The current client retries up to three times with increasing delay and marks the message failed if no acknowledgement arrives.
