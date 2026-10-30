use anchor_lang::prelude::*;
use anchor_spl::associated_token::get_associated_token_address;
use anchor_spl::token::{self, CloseAccount, Token, TokenAccount, Transfer};

use crate::error::BillError;
use crate::state::{Bill, BillStatus};

#[derive(Accounts)]
pub struct CloseBill<'info> {
    #[account(mut)]
    pub payee: Signer<'info>,

    #[account(
        mut,
        close = payee,
        has_one = payee @ BillError::Unauthorized,
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
        constraint = payee_token_account.owner == bill.payee @ BillError::InvalidPayeeTokenAccount,
        constraint = payee_token_account.mint == bill.mint @ BillError::InvalidPayeeTokenAccount,
    )]
    pub payee_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

pub fn handler(ctx: Context<CloseBill>) -> Result<()> {
    let bill = &ctx.accounts.bill;

    match bill.status {
        BillStatus::Settled
        | BillStatus::Released
        | BillStatus::Refunded
        | BillStatus::Forfeited => {}
        BillStatus::Cancelled => {
            let all_refunded = bill.shares.iter().all(|s| !s.paid || s.refunded);
            require!(all_refunded, BillError::UnrefundedSharesRemain);
        }
        BillStatus::Open | BillStatus::Held => {
            return err!(BillError::BillNotCloseable);
        }
    }

    let payee_key = bill.payee;
    let bill_id_bytes = bill.bill_id.to_le_bytes();
    let bump = bill.bump;
    let signer_seeds: &[&[&[u8]]] = &[&[b"bill", payee_key.as_ref(), &bill_id_bytes, &[bump]]];

    let dust = ctx.accounts.vault.amount;
    if dust > 0 {
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
            dust,
        )?;
    }

    token::close_account(CpiContext::new_with_signer(
        ctx.accounts.token_program.key(),
        CloseAccount {
            account: ctx.accounts.vault.to_account_info(),
            destination: ctx.accounts.payee.to_account_info(),
            authority: ctx.accounts.bill.to_account_info(),
        },
        signer_seeds,
    ))?;

    Ok(())
}
