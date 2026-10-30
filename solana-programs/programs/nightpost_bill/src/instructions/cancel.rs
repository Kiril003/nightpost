use anchor_lang::prelude::*;

use crate::error::BillError;
use crate::state::{Bill, BillStatus};

#[derive(Accounts)]
pub struct Cancel<'info> {
    pub caller: Signer<'info>,

    #[account(
        mut,
        seeds = [b"bill", bill.payee.as_ref(), &bill.bill_id.to_le_bytes()],
        bump = bill.bump,
    )]
    pub bill: Account<'info, Bill>,
}

pub fn handler(ctx: Context<Cancel>) -> Result<()> {
    let bill = &mut ctx.accounts.bill;
    require!(bill.status == BillStatus::Open, BillError::BillNotOpen);

    if ctx.accounts.caller.key() != bill.payee {
        let now = Clock::get()?.unix_timestamp;
        require!(now > bill.deadline, BillError::NotYetCancellable);
    }

    bill.status = BillStatus::Cancelled;
    Ok(())
}
