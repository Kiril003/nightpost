use anchor_lang::prelude::*;
use anchor_spl::associated_token::get_associated_token_address;
use anchor_spl::token::{self, Token, TokenAccount, Transfer};

use crate::error::BillError;
use crate::state::{Bill, BillMode, BillStatus};

#[derive(Accounts)]
pub struct RefundHold<'info> {
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
        constraint = payer_token_account.mint == bill.mint @ BillError::InvalidPayerTokenAccount,
    )]
    pub payer_token_account: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
}

pub fn handler(ctx: Context<RefundHold>) -> Result<()> {
    let bill = &ctx.accounts.bill;
    require!(bill.mode == BillMode::Hold, BillError::InvalidMode);
    require!(bill.status == BillStatus::Held, BillError::BillNotHeld);

    let share = bill.shares.first().ok_or(BillError::IndexOutOfRange)?;
    require_keys_eq!(
        ctx.accounts.payer_token_account.owner,
        share.payer,
        BillError::InvalidPayerTokenAccount
    );

    let caller = ctx.accounts.caller.key();
    let now = Clock::get()?.unix_timestamp;

    if caller == bill.payee {
        // Payee can refund at any time.
    } else if caller == share.payer {
        // Payer can self-refund strictly before cutoff, or crank after deadline.
        if now <= bill.deadline {
            require!(now < bill.cutoff, BillError::CutoffPassed);
        }
    } else {
        // Any stranger can crank a refund to the payer strictly after deadline.
        require!(now > bill.deadline, BillError::NotYetRefundable);
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
                to: ctx.accounts.payer_token_account.to_account_info(),
                authority: ctx.accounts.bill.to_account_info(),
            },
            signer_seeds,
        ),
        amount,
    )?;

    let bill = &mut ctx.accounts.bill;
    bill.shares[0].refunded = true;
    bill.status = BillStatus::Refunded;
    Ok(())
}
