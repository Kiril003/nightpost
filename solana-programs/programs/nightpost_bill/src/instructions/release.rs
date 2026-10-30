use anchor_lang::prelude::*;
use anchor_spl::associated_token::get_associated_token_address;
use anchor_spl::token::{self, Token, TokenAccount, Transfer};

use crate::error::BillError;
use crate::state::{Bill, BillMode, BillStatus};

#[derive(Accounts)]
pub struct Release<'info> {
    pub caller: Signer<'info>,

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
        constraint = payee_token_account.owner == bill.payee @ BillError::InvalidPayeeTokenAccount,
        constraint = payee_token_account.mint == bill.mint @ BillError::InvalidPayeeTokenAccount,
    )]
    pub payee_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

pub fn handler(ctx: Context<Release>) -> Result<()> {
    let bill = &ctx.accounts.bill;
    require!(bill.mode == BillMode::Hold, BillError::InvalidMode);
    require!(bill.status == BillStatus::Held, BillError::BillNotHeld);

    let share = bill.shares.first().ok_or(BillError::IndexOutOfRange)?;
    let caller = ctx.accounts.caller.key();
    require!(
        caller == bill.payee || caller == share.payer,
        BillError::Unauthorized
    );
    if caller == bill.payee {
        require!(Clock::get()?.unix_timestamp <= bill.deadline, BillError::DeadlinePassed);
    }

    let amount = share.amount;
    let payee_key = bill.payee;
    let bill_id_bytes = bill.bill_id.to_le_bytes();
    let bump = bill.bump;
    let signer_seeds: &[&[&[u8]]] = &[&[b"bill", payee_key.as_ref(), &bill_id_bytes, &[bump]]];

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
        amount,
    )?;

    ctx.accounts.bill.status = BillStatus::Released;
    Ok(())
}
