use anchor_lang::prelude::*;

#[error_code]
pub enum BillError {
    #[msg("Share count must be 1..=16 for Split and 1 for Hold")]
    InvalidShareCount,
    #[msg("Share amount must be greater than zero")]
    ZeroShareAmount,
    #[msg("Duplicate payer in shares")]
    DuplicatePayer,
    #[msg("Deadline must be in the future")]
    InvalidDeadline,
    #[msg("Deadline exceeds maximum allowed for mode")]
    DeadlineTooFar,
    #[msg("Invalid cutoff timestamp for mode")]
    InvalidCutoff,
    #[msg("Invalid forfeit configuration for mode")]
    InvalidForfeitConfig,
    #[msg("Arithmetic overflow")]
    ArithmeticOverflow,
    #[msg("Bill is not open")]
    BillNotOpen,
    #[msg("Bill is not cancelled")]
    BillNotCancelled,
    #[msg("Bill is not held")]
    BillNotHeld,
    #[msg("Bill deadline has passed")]
    DeadlinePassed,
    #[msg("Bill cannot be cancelled by non-payee before deadline")]
    NotYetCancellable,
    #[msg("Share index is out of range")]
    IndexOutOfRange,
    #[msg("Signer is not the designated payer for this share")]
    WrongPayer,
    #[msg("Share is already paid")]
    AlreadyPaid,
    #[msg("Share amount does not match expected amount")]
    AmountMismatch,
    #[msg("Share has not been paid")]
    ShareNotPaid,
    #[msg("Share has already been refunded")]
    AlreadyRefunded,
    #[msg("Bill cannot be closed in its current state")]
    BillNotCloseable,
    #[msg("Paid shares remain unrefunded")]
    UnrefundedSharesRemain,
    #[msg("Invalid vault token account")]
    InvalidVault,
    #[msg("Invalid payee token account")]
    InvalidPayeeTokenAccount,
    #[msg("Invalid payer token account")]
    InvalidPayerTokenAccount,
    #[msg("Invalid bill mode for this instruction")]
    InvalidMode,
    #[msg("Caller is not authorized")]
    Unauthorized,
    #[msg("Cancellation cutoff has already passed")]
    CutoffPassed,
    #[msg("Cancellation cutoff has not been reached")]
    CutoffNotReached,
    #[msg("Hold cannot be cranked for refund before deadline")]
    NotYetRefundable,
    #[msg("Forfeit is not allowed on this hold")]
    ForfeitNotAllowed,
}
