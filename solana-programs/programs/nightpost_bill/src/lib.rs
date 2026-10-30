use anchor_lang::prelude::*;

pub mod error;
pub mod instructions;
pub mod state;

use instructions::*;
use state::{BillMode, ShareInput};

declare_id!("BiLL111111111111111111111111111111111111111");

#[program]
pub mod nightpost_bill {
    use super::*;

    pub fn create_bill(
        ctx: Context<CreateBill>,
        bill_id: u64,
        deadline: i64,
        shares: Vec<ShareInput>,
        mode: BillMode,
        cutoff: i64,
        forfeit_allowed: bool,
    ) -> Result<()> {
        instructions::create_bill::handler(
            ctx,
            bill_id,
            deadline,
            shares,
            mode,
            cutoff,
            forfeit_allowed,
        )
    }

    pub fn pay_share(ctx: Context<PayShare>, index: u8, expected_amount: u64) -> Result<()> {
        instructions::pay_share::handler(ctx, index, expected_amount)
    }

    pub fn cancel(ctx: Context<Cancel>) -> Result<()> {
        instructions::cancel::handler(ctx)
    }

    pub fn refund_share(ctx: Context<RefundShare>, index: u8) -> Result<()> {
        instructions::refund_share::handler(ctx, index)
    }

    pub fn release(ctx: Context<Release>) -> Result<()> {
        instructions::release::handler(ctx)
    }

    pub fn refund_hold(ctx: Context<RefundHold>) -> Result<()> {
        instructions::refund_hold::handler(ctx)
    }

    pub fn forfeit(ctx: Context<Forfeit>) -> Result<()> {
        instructions::forfeit::handler(ctx)
    }

    pub fn close_bill(ctx: Context<CloseBill>) -> Result<()> {
        instructions::close_bill::handler(ctx)
    }
}
