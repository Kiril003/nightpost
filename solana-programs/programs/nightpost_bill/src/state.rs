use anchor_lang::prelude::*;

pub const MAX_SHARES: usize = 16;
pub const MAX_SPLIT_DEADLINE_SECS: i64 = 30 * 86_400;
pub const MAX_HOLD_DEADLINE_SECS: i64 = 90 * 86_400;

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum BillMode {
    Split,
    Hold,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub enum BillStatus {
    Open,
    Settled,
    Cancelled,
    Held,
    Released,
    Refunded,
    Forfeited,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Debug)]
pub struct Share {
    pub payer: Pubkey,
    pub amount: u64,
    pub paid: bool,
    pub refunded: bool,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, Debug)]
pub struct ShareInput {
    pub payer: Pubkey,
    pub amount: u64,
}

#[account]
#[derive(InitSpace, Debug)]
pub struct Bill {
    pub payee: Pubkey,
    pub mint: Pubkey,
    pub bill_id: u64,
    pub deadline: i64,
    pub cutoff: i64,
    pub forfeit_allowed: bool,
    pub mode: BillMode,
    pub status: BillStatus,
    pub bump: u8,
    #[max_len(MAX_SHARES)]
    pub shares: Vec<Share>,
}
