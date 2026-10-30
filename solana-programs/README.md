# nightpost_bill

Group bill escrow (`Split`) and single-buyer booking deposit escrow (`Hold`) on classic SPL Token: shares sit in a PDA vault until settled, released, refunded, or forfeited.

```bash
cd solana-programs
anchor build
cargo test --test escrow
```

Tests load `target/deploy/nightpost_bill.so` into LiteSVM, so build first.

## Instructions

| Instruction | Mode | Who may sign | When |
| --- | --- | --- | --- |
| `create_bill(bill_id, deadline, shares, mode, cutoff, forfeit_allowed)` | `Split`, `Hold` | `payee` | `now < deadline <= now + cap` (`30d` Split / `90d` Hold); `Split`: `1..=16` shares, `cutoff == 0`, `!forfeit_allowed`; `Hold`: `1` share, `now < cutoff <= deadline` |
| `pay_share(index, expected_amount)` | `Split`, `Hold` | `shares[index].payer` | `status == Open`, `now <= deadline`, `!share.paid`, `share.amount == expected_amount` (`Split` auto-settles on last share; `Hold` transitions to `Held`) |
| `cancel()` | `Split`, `Hold` | `payee` or anyone | `status == Open`; `payee` at any time, anyone else when `now > deadline` |
| `refund_share(index)` | `Split` | anyone (credits `shares[index].payer`) | `status == Cancelled`, `shares[index].paid && !shares[index].refunded` |
| `release()` | `Hold` | `payee` or `shares[0].payer` | `status == Held`; `payee` only when `now <= deadline` |
| `refund_hold()` | `Hold` | `payee`, `shares[0].payer`, or anyone (credits `shares[0].payer`) | `status == Held`; `payee` any time, `payer` when `now < cutoff`, anyone when `now > deadline` |
| `forfeit()` | `Hold` | `payee` | `status == Held`, `forfeit_allowed`, `cutoff <= now <= deadline` |
| `close_bill()` | `Split`, `Hold` | `payee` | `Settled`, `Released`, `Refunded`, `Forfeited`, or `Cancelled` with all paid shares refunded; sweeps any vault dust to `payee` |
