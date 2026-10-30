# Faucet wallet (devnet test USDC)

`POST /api/solana/faucet` sends 20 USDC from one server-held wallet to a user wallet: one drip per device and per wallet per 7 days, 200 drips a day in total (`SOLANA_FAUCET_GLOBAL_DAILY`). It backs the "Get test USDC" button in the phone wallet. Without `SOLANA_FAUCET_KEY` the route answers 503 `faucet_off` and the button can only fail.

## Key format

`sponsor.load_key` accepts the secret as a JSON array of 64 numbers (what `solana-keygen` writes: 32 secret bytes then the 32-byte public key) or the same 64 bytes in base58. The public half must match the secret half or the key is ignored and the route answers 503.

## Make the key (devnet only)

```bash
python3 scripts/faucet-key.py
```

It writes `~/nightpost-secrets/faucet-devnet.json` (directory 700, file 600) and prints only the address. Run again, it keeps the file and prints the same address. Needs `python3` and `openssl`. This wallet is for the faucet only: never import a key that has held mainnet funds. The code is fixed to devnet (devnet USDC mint, no mainnet path), but the file is still a signing key.

Set `SOLANA_FAUCET_RPC_URL` to a devnet node every time. Unset, the faucet falls back to `SOLANA_RPC_URL`, which may be the mainnet node used for payments, and every drip would fail there.

## Fund it (owner, two web forms)

1. SOL: faucet.solana.com, network Devnet, paste the address. About 2 SOL pays the fees and the rent of each new user token account (about 0.002 SOL each).
2. USDC: faucet.circle.com, chain Solana, network Devnet, token USDC, paste the address. The mint is `solana_pay.MINTS["devnet"]["usdc"]` (`4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`). One drip is 20 USDC; the form shows how much one request gives.

Check the balance (replace `ADDRESS`):

```bash
curl -s https://api.devnet.solana.com -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"getTokenAccountsByOwner","params":["ADDRESS",{"mint":"4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"},{"encoding":"jsonParsed"}]}'
```

## Put it on the server

Fly:

```bash
printf 'SOLANA_FAUCET_KEY=%s\nSOLANA_FAUCET_RPC_URL=https://api.devnet.solana.com\n' "$(cat ~/nightpost-secrets/faucet-devnet.json)" | flyctl secrets import -a phantom-license --stage
```

Oracle VM: `MIGRATE.md`, step 3, "Faucet".

## Check it

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://phantom-license.fly.dev/api/solana/faucet -H 'content-type: application/json' -d '{"wallet":"1111111111111111111111111111111111","device":"smoke-test-0001"}'
```

`400` means the key is loaded (the bad wallet is refused before any money moves); `503` means no usable key. A real drip needs a real devnet wallet address and uses that wallet's weekly allowance.
