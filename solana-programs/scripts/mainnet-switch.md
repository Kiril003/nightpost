# Mainnet switch (owner only)

Run from `solana-programs/`. Agents never run these.

1. Create the program keypair and write its id into the sources:
   `solana-keygen new -o target/deploy/nightpost_bill-keypair.json && anchor keys sync`
2. Dry run, then fund the deploy wallet with the SOL it prints under "SOL NEEDED":
   `scripts/deploy.sh mainnet-beta --owner-yes --dry-run`
3. Deploy (builds, shows the SOL estimate, runs `solana program deploy`):
   `scripts/deploy.sh mainnet-beta --owner-yes`

After that, set on the server (Fly secrets, the owner runs them):
`SOLANA_CLUSTER=mainnet-beta`, `NIGHTPOST_MAINNET_OWNER_YES=1`, `NIGHTPOST_BILL_PROGRAM_ID_MAINNET=<program id>`, plus `HELIUS_API_KEY` or `SOLANA_RPC_URL_MAINNET`. Without all of them `GET /api/solana/config` keeps serving devnet or `escrow: false`.

Mainnet deploys often stall on the public RPC without a priority fee: add `--with-compute-unit-price <µlamports>` to the printed `solana program deploy` command.
The upgrade authority is the owner's default solana wallet; moving it later is the owner's call.

Devnet first: `scripts/deploy.sh devnet`. Check without any network: add `--dry-run`.
