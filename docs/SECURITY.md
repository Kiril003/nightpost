# Security model

## Messages
- Messages travel as **sealed envelopes** through a blind relay. The relay sees neither sender nor recipient, only an opaque address and a blob.
- Profiles are separate spaces on one device identity: each has its own circles, settings, privacy and inbox view.
- The computer node pairs with the phone end to end; the server is not in that path.

## Keys and secrets
- No provider key, model name or service secret is compiled into the APK. The assistant goes through the server, which maps the kind of request (chat, act, voice, summary) to a model.
- Keys on the phone live in Android Keystore-backed stores (`AiKeyStore`, `ClaimKeyStore`, `BackupKeyStore`), each sealed separately and covered by tests.
- Point claims are signed by a **separate claims key**, never the wallet key.
- The repository holds only `*.env.example` files with empty values. The server reads secrets from its own environment on the VM.

## Money
- Solana **devnet** only. The mainnet switch sits behind an explicit confirmation, and the server refuses mainnet unless its operator flag is set.
- The server verifies a payment by reading the transaction from the chain itself. It never trusts what the phone reports.
- External wallets connect through Mobile Wallet Adapter / Seed Vault. The app never sees their seed.

## Backup
- The backup is an encrypted snapshot (profile, circles, chats). It is encrypted on the phone before upload, and the server stores ciphertext only.

## Server
- Rate limits on every public write route. Validation errors return 4xx without stack traces.
- Builder pages are rendered from a block registry. User content is data, never markup.
