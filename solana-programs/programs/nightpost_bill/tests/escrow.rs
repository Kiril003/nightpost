use std::path::PathBuf;

use anchor_lang::prelude::{AccountMeta, Clock, Pubkey};
use anchor_lang::solana_program::instruction::Instruction;
use anchor_lang::system_program::ID as SYSTEM_PROGRAM_ID;
use anchor_lang::{AccountDeserialize, InstructionData, ToAccountMetas};
use anchor_spl::associated_token::{
    get_associated_token_address, ID as ASSOCIATED_TOKEN_PROGRAM_ID,
};
use anchor_spl::token::ID as TOKEN_PROGRAM_ID;
use litesvm::LiteSVM;
use nightpost_bill::error::BillError;
use nightpost_bill::state::{
    Bill, BillMode, BillStatus, ShareInput, MAX_HOLD_DEADLINE_SECS, MAX_SPLIT_DEADLINE_SECS,
};
use solana_instruction_error::InstructionError;
use solana_keypair::Keypair;
use solana_signer::Signer;
use solana_transaction::Transaction;
use solana_transaction_error::TransactionError;

const TOKEN_2022_PROGRAM_ID: Pubkey =
    Pubkey::from_str_const("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

const MINT_LEN: usize = 82;

fn program_so_path() -> PathBuf {
    let mut path = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    path.pop();
    path.pop();
    path.push("target/deploy/nightpost_bill.so");
    path
}

fn setup_svm() -> LiteSVM {
    let mut svm = LiteSVM::new();
    svm.add_program_from_file(nightpost_bill::ID, program_so_path())
        .expect("load nightpost_bill.so");
    set_clock(&mut svm, 1_000);
    svm
}

fn set_clock(svm: &mut LiteSVM, unix_timestamp: i64) {
    let mut clock = svm.get_sysvar::<Clock>();
    clock.unix_timestamp = unix_timestamp;
    svm.set_sysvar::<Clock>(&clock);
}

fn funded_keypair(svm: &mut LiteSVM) -> Keypair {
    let kp = Keypair::new();
    svm.airdrop(&kp.pubkey(), 10_000_000_000).unwrap();
    kp
}

fn send_tx(
    svm: &mut LiteSVM,
    ixs: &[Instruction],
    payer: &Keypair,
    signers: &[&Keypair],
) -> Result<(), TransactionError> {
    svm.expire_blockhash();
    let blockhash = svm.latest_blockhash();
    let tx = Transaction::new_signed_with_payer(ixs, Some(&payer.pubkey()), signers, blockhash);
    svm.send_transaction(tx).map(|_| ()).map_err(|e| e.err)
}

fn assert_anchor_err(res: Result<(), TransactionError>, expected: BillError) {
    let err = res.expect_err("expected transaction to fail");
    let expected_code = u32::from(expected);
    match err {
        TransactionError::InstructionError(_, InstructionError::Custom(code)) => {
            assert_eq!(
                code, expected_code,
                "expected BillError {:?} ({}), got custom error {}",
                expected, expected_code, code
            );
        }
        other => panic!(
            "expected Custom({}), got {:?}",
            expected_code, other
        ),
    }
}

fn system_create_account_ix(
    from: &Pubkey,
    to: &Pubkey,
    lamports: u64,
    space: u64,
    owner: &Pubkey,
) -> Instruction {
    let mut data = Vec::with_capacity(4 + 8 + 8 + 32);
    data.extend_from_slice(&0u32.to_le_bytes());
    data.extend_from_slice(&lamports.to_le_bytes());
    data.extend_from_slice(&space.to_le_bytes());
    data.extend_from_slice(owner.as_ref());
    Instruction {
        program_id: SYSTEM_PROGRAM_ID,
        accounts: vec![AccountMeta::new(*from, true), AccountMeta::new(*to, true)],
        data,
    }
}

fn create_ata_idempotent_ix(payer: &Pubkey, wallet: &Pubkey, mint: &Pubkey) -> Instruction {
    let ata = get_associated_token_address(wallet, mint);
    Instruction {
        program_id: ASSOCIATED_TOKEN_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*payer, true),
            AccountMeta::new(ata, false),
            AccountMeta::new_readonly(*wallet, false),
            AccountMeta::new_readonly(*mint, false),
            AccountMeta::new_readonly(SYSTEM_PROGRAM_ID, false),
            AccountMeta::new_readonly(TOKEN_PROGRAM_ID, false),
        ],
        data: vec![1],
    }
}

fn initialize_mint2_ix(
    mint: &Pubkey,
    decimals: u8,
    mint_authority: &Pubkey,
    freeze_authority: Option<&Pubkey>,
) -> Instruction {
    let mut data = Vec::with_capacity(67);
    data.push(20u8);
    data.push(decimals);
    data.extend_from_slice(mint_authority.as_ref());
    match freeze_authority {
        Some(auth) => {
            data.push(1u8);
            data.extend_from_slice(auth.as_ref());
        }
        None => {
            data.push(0u8);
        }
    }
    Instruction {
        program_id: TOKEN_PROGRAM_ID,
        accounts: vec![AccountMeta::new(*mint, false)],
        data,
    }
}

fn mint_to_ix(
    mint: &Pubkey,
    dest: &Pubkey,
    authority: &Pubkey,
    amount: u64,
) -> Instruction {
    let mut data = Vec::with_capacity(9);
    data.push(7u8);
    data.extend_from_slice(&amount.to_le_bytes());
    Instruction {
        program_id: TOKEN_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*mint, false),
            AccountMeta::new(*dest, false),
            AccountMeta::new_readonly(*authority, true),
        ],
        data,
    }
}

fn freeze_account_ix(
    account: &Pubkey,
    mint: &Pubkey,
    freeze_authority: &Pubkey,
) -> Instruction {
    Instruction {
        program_id: TOKEN_PROGRAM_ID,
        accounts: vec![
            AccountMeta::new(*account, false),
            AccountMeta::new_readonly(*mint, false),
            AccountMeta::new_readonly(*freeze_authority, true),
        ],
        data: vec![10u8],
    }
}

fn create_mint(svm: &mut LiteSVM, authority: &Keypair, freeze_authority: Option<&Pubkey>) -> Pubkey {
    let mint = Keypair::new();
    let rent = svm.minimum_balance_for_rent_exemption(MINT_LEN);
    let create_ix = system_create_account_ix(
        &authority.pubkey(),
        &mint.pubkey(),
        rent,
        MINT_LEN as u64,
        &TOKEN_PROGRAM_ID,
    );
    let init_ix = initialize_mint2_ix(&mint.pubkey(), 6, &authority.pubkey(), freeze_authority);
    send_tx(svm, &[create_ix, init_ix], authority, &[authority, &mint]).unwrap();
    mint.pubkey()
}

fn create_ata_and_mint(
    svm: &mut LiteSVM,
    mint_authority: &Keypair,
    mint: &Pubkey,
    owner: &Pubkey,
    amount: u64,
) -> Pubkey {
    let ata = get_associated_token_address(owner, mint);
    let create_ix = create_ata_idempotent_ix(&mint_authority.pubkey(), owner, mint);
    let mut ixs = vec![create_ix];
    if amount > 0 {
        let mint_ix = mint_to_ix(mint, &ata, &mint_authority.pubkey(), amount);
        ixs.push(mint_ix);
    }
    send_tx(svm, &ixs, mint_authority, &[mint_authority]).unwrap();
    ata
}

fn token_balance(svm: &LiteSVM, account: &Pubkey) -> u64 {
    let acc = svm.get_account(account).expect("token account exists");
    assert!(acc.data.len() >= 72, "invalid SPL token account data");
    u64::from_le_bytes(acc.data[64..72].try_into().unwrap())
}

fn get_bill(svm: &LiteSVM, bill_pda: &Pubkey) -> Bill {
    let acc = svm.get_account(bill_pda).expect("bill account exists");
    let mut slice: &[u8] = &acc.data;
    Bill::try_deserialize(&mut slice).unwrap()
}

fn bill_pda(payee: &Pubkey, bill_id: u64) -> Pubkey {
    Pubkey::find_program_address(
        &[b"bill", payee.as_ref(), &bill_id.to_le_bytes()],
        &nightpost_bill::ID,
    )
    .0
}

fn create_bill_ix(
    payee: &Pubkey,
    mint: &Pubkey,
    bill_id: u64,
    deadline: i64,
    shares: Vec<ShareInput>,
    mode: BillMode,
    cutoff: i64,
    forfeit_allowed: bool,
) -> (Pubkey, Pubkey, Instruction) {
    let bill = bill_pda(payee, bill_id);
    let vault = get_associated_token_address(&bill, mint);
    let ix = Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::CreateBill {
            payee: *payee,
            bill,
            mint: *mint,
            vault,
            token_program: TOKEN_PROGRAM_ID,
            associated_token_program: ASSOCIATED_TOKEN_PROGRAM_ID,
            system_program: SYSTEM_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::CreateBill {
            bill_id,
            deadline,
            shares,
            mode,
            cutoff,
            forfeit_allowed,
        }
        .data(),
    };
    (bill, vault, ix)
}

fn pay_share_ix(
    payer: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payer_token_account: &Pubkey,
    payee_token_account: &Pubkey,
    index: u8,
    expected_amount: u64,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::PayShare {
            payer: *payer,
            bill: *bill,
            vault: *vault,
            payer_token_account: *payer_token_account,
            payee_token_account: *payee_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::PayShare {
            index,
            expected_amount,
        }
        .data(),
    }
}

fn cancel_ix(caller: &Pubkey, bill: &Pubkey) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::Cancel {
            caller: *caller,
            bill: *bill,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::Cancel {}.data(),
    }
}

fn refund_share_ix(
    caller: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payer_token_account: &Pubkey,
    index: u8,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::RefundShare {
            caller: *caller,
            bill: *bill,
            vault: *vault,
            payer_token_account: *payer_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::RefundShare { index }.data(),
    }
}

fn release_ix(
    caller: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payee_token_account: &Pubkey,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::Release {
            caller: *caller,
            bill: *bill,
            vault: *vault,
            payee_token_account: *payee_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::Release {}.data(),
    }
}

fn refund_hold_ix(
    caller: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payer_token_account: &Pubkey,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::RefundHold {
            caller: *caller,
            bill: *bill,
            vault: *vault,
            payer_token_account: *payer_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::RefundHold {}.data(),
    }
}

fn forfeit_ix(
    payee: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payee_token_account: &Pubkey,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::Forfeit {
            payee: *payee,
            bill: *bill,
            vault: *vault,
            payee_token_account: *payee_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::Forfeit {}.data(),
    }
}

fn close_bill_ix(
    payee: &Pubkey,
    bill: &Pubkey,
    vault: &Pubkey,
    payee_token_account: &Pubkey,
) -> Instruction {
    Instruction {
        program_id: nightpost_bill::ID,
        accounts: nightpost_bill::accounts::CloseBill {
            payee: *payee,
            bill: *bill,
            vault: *vault,
            payee_token_account: *payee_token_account,
            token_program: TOKEN_PROGRAM_ID,
        }
        .to_account_metas(None),
        data: nightpost_bill::instruction::CloseBill {}.data(),
    }
}

// ============================================================================
// Baseline Split tests
// ============================================================================

#[test]
fn happy_path_two_shares_auto_settles_and_closes() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 500);
    let b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 700);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        1,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 500,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 700,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Open);
    assert_eq!(token_balance(&svm, &vault), 500);

    let pay_b = pay_share_ix(&payer_b.pubkey(), &bill, &vault, &b_ata, &payee_ata, 1, 700);
    send_tx(&mut svm, &[pay_b], &payer_b, &[&payer_b]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Settled);
    assert_eq!(token_balance(&svm, &payee_ata), 1_200);
    assert_eq!(token_balance(&svm, &vault), 0);

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn expired_bill_cancelled_by_stranger_and_refunded() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 400);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 600);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        2,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 400,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 600,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();

    set_clock(&mut svm, 2_001);
    let cancel = cancel_ix(&stranger.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &stranger, &[&stranger]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Cancelled);

    let refund = refund_share_ix(&stranger.pubkey(), &bill, &vault, &a_ata, 0);
    send_tx(&mut svm, &[refund], &stranger, &[&stranger]).unwrap();
    assert_eq!(token_balance(&svm, &a_ata), 400);

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert!(svm.get_account(&bill).is_none());
}

// ============================================================================
// Part A: Hardening tests
// ============================================================================

#[test]
fn token_2022_mint_rejected_at_create() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    // Build a Token-2022 mint with TransferFeeConfig extension layout by hand, owned by Token-2022.
    let mint_kp = Keypair::new();
    let mut data = vec![0u8; 278];
    // Mint authority COption::Some (1) + pubkey
    data[0..4].copy_from_slice(&1u32.to_le_bytes());
    data[4..36].copy_from_slice(mint_auth.pubkey().as_ref());
    // Decimals = 6, is_initialized = 1
    data[44] = 6;
    data[45] = 1;
    // AccountType::Mint = 1 at offset 165, followed by ExtensionType::TransferFeeConfig = 1
    data[165] = 1;
    data[166..168].copy_from_slice(&1u16.to_le_bytes());
    data[168..170].copy_from_slice(&108u16.to_le_bytes());

    let rent = svm.minimum_balance_for_rent_exemption(data.len());
    svm.set_account(
        mint_kp.pubkey(),
        solana_account::Account {
            lamports: rent,
            data,
            owner: TOKEN_2022_PROGRAM_ID,
            executable: false,
            rent_epoch: 0,
        },
    )
    .unwrap();

    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint_kp.pubkey(),
        10,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert!(res.is_err(), "Token-2022 mint must be rejected at create");
}

#[test]
fn pay_with_wrong_expected_amount_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        11,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let bad_pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        499,
    );
    let res = send_tx(&mut svm, &[bad_pay], &payer, &[&payer]);
    assert_anchor_err(res, BillError::AmountMismatch);
}

#[test]
fn reuse_bill_id_after_close_with_bigger_share_rejects_old_signed_amount() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 10_000);

    let bill_id = 42u64;
    let (bill, vault, create_1) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        bill_id,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_1], &payee, &[&payee]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[cancel, close], &payee, &[&payee]).unwrap();

    // Payee re-creates the same bill_id with a much larger share amount (9_000).
    let (_bill2, _vault2, create_2) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        bill_id,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 9_000,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_2], &payee, &[&payee]).unwrap();

    // Payer submits a pay_share expecting the old 100 amount; must fail with AmountMismatch.
    let pay_old = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        100,
    );
    let res = send_tx(&mut svm, &[pay_old], &payer, &[&payer]);
    assert_anchor_err(res, BillError::AmountMismatch);
    assert_eq!(token_balance(&svm, &payer_ata), 10_000);
}

#[test]
fn split_deadline_at_cap_succeeds() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let now = 1_000i64;
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        20,
        now + MAX_SPLIT_DEADLINE_SECS,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();
}

#[test]
fn split_deadline_past_cap_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let now = 1_000i64;
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        21,
        now + MAX_SPLIT_DEADLINE_SECS + 1,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::DeadlineTooFar);
}

#[test]
fn hold_deadline_at_cap_succeeds() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let now = 1_000i64;
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        22,
        now + MAX_HOLD_DEADLINE_SECS,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Hold,
        now + 3_600,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();
}

#[test]
fn hold_deadline_past_cap_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let now = 1_000i64;
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        23,
        now + MAX_HOLD_DEADLINE_SECS + 1,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Hold,
        now + 3_600,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::DeadlineTooFar);
}

#[test]
fn dust_in_vault_does_not_block_close_cancelled() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        30,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    // Stranger mints 77 units of dust directly into the vault.
    let dust_ix = mint_to_ix(&mint, &vault, &mint_auth.pubkey(), 77);
    send_tx(&mut svm, &[dust_ix], &mint_auth, &[&mint_auth]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[cancel, close], &payee, &[&payee]).unwrap();

    assert_eq!(token_balance(&svm, &payee_ata), 77);
    assert_eq!(token_balance(&svm, &payer_ata), 500);
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn recreated_vault_after_settle_does_not_block_close() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        31,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Settled);
    assert_eq!(token_balance(&svm, &payee_ata), 500);

    // Stranger idempotently creates/uses the vault ATA and deposits 33 dust after settle.
    create_ata_and_mint(&mut svm, &mint_auth, &mint, &bill, 33);
    assert_eq!(token_balance(&svm, &vault), 33);

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert_eq!(token_balance(&svm, &payee_ata), 533);
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn close_cannot_sweep_a_live_payers_share() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 500);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        32,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 500,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 500,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    // Payee tries to close without refunding payer_a; must fail and vault keeps the 500.
    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[close], &payee, &[&payee]);
    assert_anchor_err(res, BillError::UnrefundedSharesRemain);
    assert_eq!(token_balance(&svm, &vault), 500);
    assert_eq!(token_balance(&svm, &payee_ata), 0);
}

#[test]
fn precreated_vault_ata_does_not_block_create() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let bill_id = 33u64;
    let bill = bill_pda(&payee.pubkey(), bill_id);

    // Front-runner pre-creates the vault ATA before create_bill.
    let pre_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &bill, 5);
    assert_eq!(token_balance(&svm, &pre_ata), 5);

    let (_bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        bill_id,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    assert_eq!(vault, pre_ata);
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Open);
}

#[test]
fn stranger_cancel_before_deadline_returns_not_yet_cancellable() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        34,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let cancel = cancel_ix(&stranger.pubkey(), &bill);
    let res = send_tx(&mut svm, &[cancel], &stranger, &[&stranger]);
    assert_anchor_err(res, BillError::NotYetCancellable);
}

#[test]
fn pay_wrong_signer_returns_wrong_payer() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let wrong_signer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let wrong_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &wrong_signer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        35,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &wrong_signer.pubkey(),
        &bill,
        &vault,
        &wrong_ata,
        &payee_ata,
        0,
        500,
    );
    let res = send_tx(&mut svm, &[pay], &wrong_signer, &[&wrong_signer]);
    assert_anchor_err(res, BillError::WrongPayer);
}

// ============================================================================
// Part B: Hold mode tests (ARCH §9)
// ============================================================================

#[test]
fn hold_pay_does_not_settle() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        100,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let b = get_bill(&svm, &bill);
    assert_eq!(b.status, BillStatus::Held);
    assert_eq!(token_balance(&svm, &vault), 1_000);
    assert_eq!(token_balance(&svm, &payee_ata), 0);
}

#[test]
fn hold_release_by_payee() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        101,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rel = release_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[rel], &payee, &[&payee]).unwrap();

    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Released);
    assert_eq!(token_balance(&svm, &payee_ata), 1_000);
    assert_eq!(token_balance(&svm, &vault), 0);
}

#[test]
fn hold_release_by_payee_after_deadline_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        101,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 5_001);
    let rel = release_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    assert_anchor_err(send_tx(&mut svm, &[rel], &payee, &[&payee]), BillError::DeadlinePassed);
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Held);
    assert_eq!(token_balance(&svm, &vault), 1_000);

    let rel = release_ix(&payer.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[rel], &payer, &[&payer]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Released);
    assert_eq!(token_balance(&svm, &payee_ata), 1_000);
}

#[test]
fn hold_release_by_payer() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        102,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rel = release_ix(&payer.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[rel], &payer, &[&payer]).unwrap();

    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Released);
    assert_eq!(token_balance(&svm, &payee_ata), 1_000);
}

#[test]
fn hold_release_by_stranger_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        103,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rel = release_ix(&stranger.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[rel], &stranger, &[&stranger]);
    assert_anchor_err(res, BillError::Unauthorized);
}

#[test]
fn hold_refund_by_payee_any_time() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        104,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    // Advance past cutoff; payee can still refund at any time.
    set_clock(&mut svm, 3_500);
    let rf = refund_hold_ix(&payee.pubkey(), &bill, &vault, &payer_ata);
    send_tx(&mut svm, &[rf], &payee, &[&payee]).unwrap();

    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Refunded);
    assert_eq!(token_balance(&svm, &payer_ata), 1_000);
    assert_eq!(token_balance(&svm, &vault), 0);
}

#[test]
fn hold_refund_by_payer_before_cutoff() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        105,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 2_999);
    let rf = refund_hold_ix(&payer.pubkey(), &bill, &vault, &payer_ata);
    send_tx(&mut svm, &[rf], &payer, &[&payer]).unwrap();

    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Refunded);
    assert_eq!(token_balance(&svm, &payer_ata), 1_000);
}

#[test]
fn hold_refund_by_payer_after_cutoff_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        106,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 3_000);
    let rf = refund_hold_ix(&payer.pubkey(), &bill, &vault, &payer_ata);
    let res = send_tx(&mut svm, &[rf], &payer, &[&payer]);
    assert_anchor_err(res, BillError::CutoffPassed);
}

#[test]
fn hold_refund_crank_by_stranger_after_deadline_goes_to_payer() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);
    let stranger_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &stranger.pubkey(), 0);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        107,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 5_001);

    // Stranger cannot redirect funds to stranger_ata.
    let bad_rf = refund_hold_ix(&stranger.pubkey(), &bill, &vault, &stranger_ata);
    let bad_res = send_tx(&mut svm, &[bad_rf], &stranger, &[&stranger]);
    assert_anchor_err(bad_res, BillError::InvalidPayerTokenAccount);

    // Stranger crank to payer_ata succeeds and returns 1_000 to payer.
    let rf = refund_hold_ix(&stranger.pubkey(), &bill, &vault, &payer_ata);
    send_tx(&mut svm, &[rf], &stranger, &[&stranger]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Refunded);
    assert_eq!(token_balance(&svm, &payer_ata), 1_000);
    assert_eq!(token_balance(&svm, &stranger_ata), 0);
}

#[test]
fn hold_refund_crank_before_deadline_by_stranger_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        108,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 5_000);
    let rf = refund_hold_ix(&stranger.pubkey(), &bill, &vault, &payer_ata);
    let res = send_tx(&mut svm, &[rf], &stranger, &[&stranger]);
    assert_anchor_err(res, BillError::NotYetRefundable);
}

#[test]
fn hold_forfeit_after_cutoff_when_allowed() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        109,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 3_000);
    let ff = forfeit_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[ff], &payee, &[&payee]).unwrap();

    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Forfeited);
    assert_eq!(token_balance(&svm, &payee_ata), 1_000);
    assert_eq!(token_balance(&svm, &vault), 0);

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn hold_forfeit_when_not_allowed_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        110,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 3_500);
    let ff = forfeit_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[ff], &payee, &[&payee]);
    assert_anchor_err(res, BillError::ForfeitNotAllowed);
}

#[test]
fn hold_forfeit_before_cutoff_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        111,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 2_999);
    let ff = forfeit_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[ff], &payee, &[&payee]);
    assert_anchor_err(res, BillError::CutoffNotReached);
}

#[test]
fn hold_forfeit_by_payer_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        112,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 3_500);
    let ff = forfeit_ix(&payer.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[ff], &payer, &[&payer]);
    assert_anchor_err(res, BillError::Unauthorized);
}

#[test]
fn hold_with_two_shares_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        113,
        5_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 500,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 500,
            },
        ],
        BillMode::Hold,
        3_000,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidShareCount);
}

#[test]
fn hold_cutoff_after_deadline_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        114,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Hold,
        5_001,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidCutoff);
}

#[test]
fn hold_double_release_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        115,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rel1 = release_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[rel1], &payee, &[&payee]).unwrap();

    let rel2 = release_ix(&payer.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[rel2], &payer, &[&payer]);
    assert_anchor_err(res, BillError::BillNotHeld);
}

#[test]
fn hold_refund_after_release_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        116,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rel = release_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[rel], &payee, &[&payee]).unwrap();

    let rf = refund_hold_ix(&payee.pubkey(), &bill, &vault, &payer_ata);
    let res = send_tx(&mut svm, &[rf], &payee, &[&payee]);
    assert_anchor_err(res, BillError::BillNotHeld);
}

#[test]
fn hold_close_after_refund() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        117,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Hold,
        3_000,
        true,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_ata,
        0,
        1_000,
    );
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rf = refund_hold_ix(&payer.pubkey(), &bill, &vault, &payer_ata);
    send_tx(&mut svm, &[rf], &payer, &[&payer]).unwrap();

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn split_create_with_cutoff_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        118,
        5_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 1_000,
        }],
        BillMode::Split,
        2_000,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidCutoff);
}

// ============================================================================
// Part C: Coverage tests from the review
// ============================================================================

#[test]
fn create_with_past_deadline_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        200,
        999,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 100,
        }],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidDeadline);
}

#[test]
fn create_total_overflow_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        201,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: u64::MAX,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 1,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::ArithmeticOverflow);
}

#[test]
fn create_sixteen_shares_succeeds() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let shares: Vec<ShareInput> = (0..16)
        .map(|_| ShareInput {
            payer: Keypair::new().pubkey(),
            amount: 100,
        })
        .collect();

    let (bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        202,
        2_000,
        shares,
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();
    assert_eq!(get_bill(&svm, &bill).shares.len(), 16);
}

#[test]
fn pay_after_cancel_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        203,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    let res = send_tx(&mut svm, &[pay], &payer, &[&payer]);
    assert_anchor_err(res, BillError::BillNotOpen);
}

#[test]
fn pay_after_settled_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        204,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay1 = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay1], &payer, &[&payer]).unwrap();
    assert_eq!(get_bill(&svm, &bill).status, BillStatus::Settled);

    let pay2 = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    let res = send_tx(&mut svm, &[pay2], &payer, &[&payer]);
    assert_anchor_err(res, BillError::BillNotOpen);
}

#[test]
fn pay_index_out_of_range_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        205,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 5, 500);
    let res = send_tx(&mut svm, &[pay], &payer, &[&payer]);
    assert_anchor_err(res, BillError::IndexOutOfRange);
}

#[test]
fn refund_on_open_bill_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 500);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        206,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 500,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 500,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();

    let rf = refund_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, 0);
    let res = send_tx(&mut svm, &[rf], &payer_a, &[&payer_a]);
    assert_anchor_err(res, BillError::BillNotCancelled);
}

#[test]
fn refund_on_settled_bill_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        207,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    let rf = refund_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, 0);
    let res = send_tx(&mut svm, &[rf], &payer, &[&payer]);
    assert_anchor_err(res, BillError::BillNotCancelled);
}

#[test]
fn vault_of_other_bill_rejected_in_pay() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 1_000);

    let (bill_a, _vault_a, create_a) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        208,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    let (_bill_b, vault_b, create_b) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        209,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_a, create_b], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill_a,
        &vault_b,
        &payer_ata,
        &payee_ata,
        0,
        500,
    );
    let res = send_tx(&mut svm, &[pay], &payer, &[&payer]);
    assert_anchor_err(res, BillError::InvalidVault);
}

#[test]
fn vault_of_other_bill_rejected_in_refund() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 1_000);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 1_000);

    let (bill_1, vault_1, create_1) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        210,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 500,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 500,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    let (_bill_2, vault_2, create_2) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        211,
        2_000,
        vec![ShareInput {
            payer: payer_a.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_1, create_2], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill_1, &vault_1, &a_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill_1);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let bad_rf = refund_share_ix(&payer_a.pubkey(), &bill_1, &vault_2, &a_ata, 0);
    let res = send_tx(&mut svm, &[bad_rf], &payer_a, &[&payer_a]);
    assert_anchor_err(res, BillError::InvalidVault);
}

#[test]
fn vault_of_other_bill_rejected_in_close() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);

    let (bill_1, _vault_1, create_1) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        212,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    let (_bill_2, vault_2, create_2) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        213,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_1, create_2], &payee, &[&payee]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill_1);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let bad_close = close_bill_ix(&payee.pubkey(), &bill_1, &vault_2, &payee_ata);
    let res = send_tx(&mut svm, &[bad_close], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidVault);
}

#[test]
fn close_with_wrong_vault_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        214,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let bad_close = close_bill_ix(&payee.pubkey(), &bill, &payer_ata, &payee_ata);
    let res = send_tx(&mut svm, &[bad_close], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidVault);
}

#[test]
fn payee_token_account_wrong_mint_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);

    let mint_1 = create_mint(&mut svm, &mint_auth, None);
    let mint_2 = create_mint(&mut svm, &mint_auth, None);
    let payee_wrong_mint_ata =
        create_ata_and_mint(&mut svm, &mint_auth, &mint_2, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint_1, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint_1,
        215,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(
        &payer.pubkey(),
        &bill,
        &vault,
        &payer_ata,
        &payee_wrong_mint_ata,
        0,
        500,
    );
    let res = send_tx(&mut svm, &[pay], &payer, &[&payer]);
    assert_anchor_err(res, BillError::InvalidPayeeTokenAccount);
}

#[test]
fn stranger_cancel_after_settle_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let stranger = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let payer_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer.pubkey(), 500);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        216,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 500,
        }],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer.pubkey(), &bill, &vault, &payer_ata, &payee_ata, 0, 500);
    send_tx(&mut svm, &[pay], &payer, &[&payer]).unwrap();

    set_clock(&mut svm, 3_000);
    let cancel = cancel_ix(&stranger.pubkey(), &bill);
    let res = send_tx(&mut svm, &[cancel], &stranger, &[&stranger]);
    assert_anchor_err(res, BillError::BillNotOpen);
}

#[test]
fn payee_cancel_with_partial_pays_then_refund_and_close() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 400);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 600);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        217,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 400,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 600,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();
    assert_eq!(token_balance(&svm, &a_ata), 0);

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let rf = refund_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, 0);
    send_tx(&mut svm, &[rf], &payer_a, &[&payer_a]).unwrap();
    assert_eq!(token_balance(&svm, &a_ata), 400);

    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    send_tx(&mut svm, &[close], &payee, &[&payee]).unwrap();
    assert!(svm.get_account(&bill).is_none());
}

#[test]
fn unrefundable_frozen_payer_blocks_close() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let freeze_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, Some(&freeze_auth.pubkey()));
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 400);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 600);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        218,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 400,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 600,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay_a = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    send_tx(&mut svm, &[pay_a], &payer_a, &[&payer_a]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    // Freeze payer_a's token account.
    let freeze_ix = freeze_account_ix(&a_ata, &mint, &freeze_auth.pubkey());
    send_tx(&mut svm, &[freeze_ix], &freeze_auth, &[&freeze_auth]).unwrap();

    // Refund fails because payer_a's token account is frozen.
    let rf = refund_share_ix(&payee.pubkey(), &bill, &vault, &a_ata, 0);
    assert!(send_tx(&mut svm, &[rf], &payee, &[&payee]).is_err());

    // Close also fails because payer_a's share is still unrefunded in the vault.
    let close = close_bill_ix(&payee.pubkey(), &bill, &vault, &payee_ata);
    let res = send_tx(&mut svm, &[close], &payee, &[&payee]);
    assert_anchor_err(res, BillError::UnrefundedSharesRemain);
    assert_eq!(token_balance(&svm, &vault), 400);
}

#[test]
fn duplicate_payer_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        300,
        2_000,
        vec![
            ShareInput {
                payer: payer.pubkey(),
                amount: 100,
            },
            ShareInput {
                payer: payer.pubkey(),
                amount: 200,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::DuplicatePayer);
}

#[test]
fn zero_share_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        301,
        2_000,
        vec![ShareInput {
            payer: payer.pubkey(),
            amount: 0,
        }],
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::ZeroShareAmount);
}

#[test]
fn too_many_shares_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let mint = create_mint(&mut svm, &mint_auth, None);

    let shares: Vec<ShareInput> = (0..17)
        .map(|_| ShareInput {
            payer: Keypair::new().pubkey(),
            amount: 100,
        })
        .collect();

    let (_bill, _vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        302,
        2_000,
        shares,
        BillMode::Split,
        0,
        false,
    );
    let res = send_tx(&mut svm, &[create_ix], &payee, &[&payee]);
    assert_anchor_err(res, BillError::InvalidShareCount);
}

#[test]
fn double_pay_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 1_000);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 1_000);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        303,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 400,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 600,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay1 = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    send_tx(&mut svm, &[pay1], &payer_a, &[&payer_a]).unwrap();

    let pay2 = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    let res = send_tx(&mut svm, &[pay2], &payer_a, &[&payer_a]);
    assert_anchor_err(res, BillError::AlreadyPaid);
}

#[test]
fn double_refund_rejected() {
    let mut svm = setup_svm();
    let mint_auth = funded_keypair(&mut svm);
    let payee = funded_keypair(&mut svm);
    let payer_a = funded_keypair(&mut svm);
    let payer_b = funded_keypair(&mut svm);

    let mint = create_mint(&mut svm, &mint_auth, None);
    let payee_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payee.pubkey(), 0);
    let a_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_a.pubkey(), 400);
    let _b_ata = create_ata_and_mint(&mut svm, &mint_auth, &mint, &payer_b.pubkey(), 600);

    let (bill, vault, create_ix) = create_bill_ix(
        &payee.pubkey(),
        &mint,
        304,
        2_000,
        vec![
            ShareInput {
                payer: payer_a.pubkey(),
                amount: 400,
            },
            ShareInput {
                payer: payer_b.pubkey(),
                amount: 600,
            },
        ],
        BillMode::Split,
        0,
        false,
    );
    send_tx(&mut svm, &[create_ix], &payee, &[&payee]).unwrap();

    let pay = pay_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, &payee_ata, 0, 400);
    send_tx(&mut svm, &[pay], &payer_a, &[&payer_a]).unwrap();

    let cancel = cancel_ix(&payee.pubkey(), &bill);
    send_tx(&mut svm, &[cancel], &payee, &[&payee]).unwrap();

    let rf1 = refund_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, 0);
    send_tx(&mut svm, &[rf1], &payer_a, &[&payer_a]).unwrap();

    let rf2 = refund_share_ix(&payer_a.pubkey(), &bill, &vault, &a_ata, 0);
    let res = send_tx(&mut svm, &[rf2], &payer_a, &[&payer_a]);
    assert_anchor_err(res, BillError::AlreadyRefunded);
}
