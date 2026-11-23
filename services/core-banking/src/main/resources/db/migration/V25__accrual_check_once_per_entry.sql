-- Increment 28 (ADR-0028): the load test showed the I4 check (accrual rows total = accrual entry)
-- is quadratic. It is a deferred FOR EACH ROW constraint trigger, so at commit it ran once per
-- accrual row and each run summed every row of the entry: 22,500 accounts in one branch meant
-- about 500 million row reads and 86 s at commit.
--
-- Deferred triggers fire after every statement of the transaction has run, so the first firing
-- for an entry already sees all of that entry's rows. Checking each entry once per transaction is
-- therefore exactly as strict. The marker is a transaction-local setting holding the transaction
-- id, so it disappears at commit or rollback and a stale session-level value never matches. Only
-- code inside the inserting transaction could forge it; that is the same trust already placed in
-- the application role for app.tenant_id, on which row-level security depends.
CREATE OR REPLACE FUNCTION savings.check_interest_accrual_entry() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_rows numeric;
    v_dr numeric;
    v_cr numeric;
    v_marker text;
    p savings.deposit_product%ROWTYPE;
BEGIN
    IF NEW.entry_id IS NULL THEN
        RETURN NULL;
    END IF;
    v_marker := 'mfi_i4.e' || replace(NEW.entry_id::text, '-', '');
    IF current_setting(v_marker, true) = txid_current()::text THEN
        RETURN NULL; -- this entry was already checked, against its complete set of rows, in this transaction
    END IF;
    SELECT * INTO p FROM savings.deposit_product WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    SELECT sum(posted) INTO v_rows FROM savings.interest_accrual WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    SELECT COALESCE(sum(amount) FILTER (WHERE side = 'D' AND account_code = p.gl_interest_expense), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = p.gl_interest_payable), 0)
      INTO v_dr, v_cr FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_dr <> v_rows OR v_cr <> v_rows THEN
        RAISE EXCEPTION 'EOD_AMOUNT_MISMATCH: accrual rows % vs entry % / %', v_rows, v_dr, v_cr USING ERRCODE = 'P0001';
    END IF;
    PERFORM set_config(v_marker, txid_current()::text, true);
    RETURN NULL;
END $$;
