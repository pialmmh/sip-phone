# Verto Test Setup — BTCL sbc1

## Server: sbc1 (10.10.194.1)

| Item | Value |
|------|-------|
| Module | mod_verto.so loaded |
| WS port | 10.10.194.1:8081 (plaintext) |
| WSS port | 10.10.194.1:8082 (TLS, self-signed) |
| SSL certs | /usr/local/freeswitch/certs/wss.pem |

## Test User

| Field | Value |
|-------|-------|
| Username | 9000 |
| Password | verto9000test |
| Domain | 10.10.194.1 |
| Context | default |
| Caller ID | Verto Test / 9000 |

## Connection Details

- **WS URL:** ws://10.10.194.1:8081
- **WSS URL:** wss://10.10.194.1:8082
- **Login:** 9000@10.10.194.1
- **Password:** verto9000test

## Testing

Can test with https://evolux.net.br/verto or any Verto-compatible WebRTC client pointing to the WS/WSS endpoint.

For our SIP Phone app:
- Server: `ws://10.10.194.1:8081` (use plaintext for initial testing to avoid self-signed cert issues)
- Username: `9000`
- Password: `verto9000test`
- Codec: `PCMU` (for first end-to-end test)
