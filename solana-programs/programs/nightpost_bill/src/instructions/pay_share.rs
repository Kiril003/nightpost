use anchor_lang::prelude::*;
use anchor_spl::associated_token::get_associated_token_address;
use anchor_spl::token::{self, Token, TokenAccount, Transfer};

use crate::error::BillError;
use crate::state::{Bill, BillMode, BillStatus};

#[derive(Accounts)]
pub struct PayShare<'info> {
    pub payer: Signer<'info>,

    #[account(
        mut,
        seeds = [b"bill", bill.payee.as_ref(), &bill.bill_id.to_le_bytes()],
        bump = bill.bump,
    )]
    pub bill: Account<'info, Bill>,

    #[account(
        mut,
        constraint = vault.owner == bill.key() @ BillError::InvalidVault,
        constraint = vault.mint == bill.mint @ BillError::InvalidVault,
        constraint = vault.key() == get_associated_token_address(&bill.key(), &bill.mint) @ BillError::InvalidVault,
    )]
    pub vault: Account<'info, TokenAccount>,

    #[account(
        mut,
        constraint = payer_token_account.owner == payer.key() @ BillError::InvalidPayerTokenAccount,
        constraint = payer_token_account.mint == bill.mint @ BillError::InvalidPayerTokenAccount,
    )]
    pub payer_token_account: Account<'info, TokenAccount>,

    #[account(
        mut,
        constraint = payee_token_account.owner == bill.payee @ BillError::InvalidPayeeTokenAccount,
        constraint = payee_token_account.mint == bill.mint @ BillError::InvalidPayeeTokenAccount,
    )]
    pub payee_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

pub fn handler(ctx: Context<PayShare>, index: u8, expected_amount: u64) -> Result<()> {
    let bill = &ctx.accounts.bill;
    require!(bill.status == BillStatus::Open, BillError::BillNotOpen);

    let now = Clock::get()?.unix_timestamp;
    require!(now <= bill.deadline, BillError::DeadlinePassed);

    let idx = usize::from(index);
    require!(idx < bill.shares.len(), BillError::IndexOutOfRange);

    let share = &bill.shares[idx];
    require_keys_eq!(share.payer, ctx.accounts.payer.key(), BillError::WrongPayer);
    require!(!share.paid, BillError::AlreadyPaid);
    require_eq!(share.amount, expected_amount, BillError::AmountMismatch);

    let share_amount = share.amount;

    token::transfer(
        CpiContext::new(
            ctx.accounts.token_program.key(),
            Transfer {
                from: ctx.accounts.payer_token_account.to_account_info(),
                to: ctx.accounts.vault.to_account_info(),
                authority: ctx.accounts.payer.to_account_info(),
            },
        ),
        share_amount,
    )?;

    let bill = &mut ctx.accounts.bill;
    bill.shares[idx].paid = true;

    match bill.mode {
        BillMode::Hold => {
            bill.status = BillStatus::Held;
        }
        BillMode::Split => {
            let all_paid = bill.shares.iter().all(|s| s.paid);
            if all_paid {
                bill.status = BillStatus::Settled;
                let mut total: u64 = 0;
                for s in &bill.shares {
                    total = total
                        .checked_add(s.amount)
                        .ok_or(BillError::ArithmeticOverflow)?;
                }

                let payee_key = bill.payee;
                let bill_id_bytes = bill.bill_id.to_le_bytes();
                let bump = bill.bump;
                let signer_seeds: &[&[&[u8]]] =
                    &[&[b"bill", payee_key.as_ref(), &bill_id_bytes, &[bump]]];

                token::transfer(
                    CpiContext::new_with_signer(
                        ctx.accounts.token_program.key(),
                        Transfer {
                            from: ctx.accounts.vault.to_account_info(),
                            to: ctx.accounts.payee_token_account.to_account_info(),
                            authority: ctx.accounts.bill.to_account_info(),
                        },
                        signer_seeds,
                    ),
                    total,
                )?;
            }
        }
    }

    Ok(())
}
