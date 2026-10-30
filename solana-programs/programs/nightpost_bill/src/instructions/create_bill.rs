use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token::{Mint, Token, TokenAccount};

use crate::error::BillError;
use crate::state::{
    Bill, BillMode, BillStatus, Share, ShareInput, MAX_HOLD_DEADLINE_SECS, MAX_SHARES,
    MAX_SPLIT_DEADLINE_SECS,
};

#[derive(Accounts)]
#[instruction(bill_id: u64)]
pub struct CreateBill<'info> {
    #[account(mut)]
    pub payee: Signer<'info>,

    #[account(
        init,
        payer = payee,
        space = 8 + Bill::INIT_SPACE,
        seeds = [b"bill", payee.key().as_ref(), &bill_id.to_le_bytes()],
        bump,
    )]
    pub bill: Account<'info, Bill>,

    pub mint: Account<'info, Mint>,

    #[account(
        init_if_needed,
        payer = payee,
        associated_token::mint = mint,
        associated_token::authority = bill,
    )]
    pub vault: Account<'info, TokenAccount>,

    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<CreateBill>,
    bill_id: u64,
    deadline: i64,
    shares: Vec<ShareInput>,
    mode: BillMode,
    cutoff: i64,
    forfeit_allowed: bool,
) -> Result<()> {
    let now = Clock::get()?.unix_timestamp;
    require!(deadline > now, BillError::InvalidDeadline);

    match mode {
        BillMode::Split => {
            let max_deadline = now
                .checked_add(MAX_SPLIT_DEADLINE_SECS)
                .ok_or(BillError::ArithmeticOverflow)?;
            require!(deadline <= max_deadline, BillError::DeadlineTooFar);
            require!(
                !shares.is_empty() && shares.len() <= MAX_SHARES,
                BillError::InvalidShareCount
            );
            require!(cutoff == 0, BillError::InvalidCutoff);
            require!(!forfeit_allowed, BillError::InvalidForfeitConfig);
        }
        BillMode::Hold => {
            let max_deadline = now
                .checked_add(MAX_HOLD_DEADLINE_SECS)
                .ok_or(BillError::ArithmeticOverflow)?;
            require!(deadline <= max_deadline, BillError::DeadlineTooFar);
            require!(shares.len() == 1, BillError::InvalidShareCount);
            require!(cutoff > now && cutoff <= deadline, BillError::InvalidCutoff);
        }
    }

    let mut total: u64 = 0;
    let mut built_shares = Vec::with_capacity(shares.len());
    for (i, input) in shares.iter().enumerate() {
        require!(input.amount > 0, BillError::ZeroShareAmount);
        for prev in &shares[..i] {
            require!(prev.payer != input.payer, BillError::DuplicatePayer);
        }
        total = total
            .checked_add(input.amount)
            .ok_or(BillError::ArithmeticOverflow)?;
        built_shares.push(Share {
            payer: input.payer,
            amount: input.amount,
            paid: false,
            refunded: false,
        });
    }

    let bill = &mut ctx.accounts.bill;
    bill.payee = ctx.accounts.payee.key();
    bill.mint = ctx.accounts.mint.key();
    bill.bill_id = bill_id;
    bill.deadline = deadline;
    bill.cutoff = cutoff;
    bill.forfeit_allowed = forfeit_allowed;
    bill.mode = mode;
    bill.status = BillStatus::Open;
    bill.bump = ctx.bumps.bill;
    bill.shares = built_shares;

    Ok(())
}
