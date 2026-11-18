-- =============================================================================
-- V9: paybill receipts can repay loans; unmatched receipts are cleared from suspense under
-- maker-checker.
--   S1  a provider payment is exactly one of: APPLIED (deposit account), LOAN_REPAYMENT (loan),
--       UNMATCHED (suspense)
--   S2  a suspense resolution is requested by one person and approved/rejected by another;
--       only UNMATCHED receipts can be resolved; at most one pending-or-approved resolution per
--       receipt; an approved resolution always carries its posted journal entry
-- =============================================================================
ALTER TABLE payments.provider_payment DROP CONSTRAINT provider_payment_status_check;
ALTER TABLE payments.provider_payment DROP CONSTRAINT provider_payment_check;
ALTER TABLE payments.provider_payment ADD COLUMN loan_id uuid;
ALTER TABLE payments.provider_payment
    ADD CONSTRAINT provider_payment_loan_fk FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    ADD CONSTRAINT provider_payment_status_check CHECK (status IN ('APPLIED', 'LOAN_REPAYMENT', 'UNMATCHED')),
    ADD CONSTRAINT provider_payment_target_check CHECK (
        (status = 'APPLIED') = (deposit_account_id IS NOT NULL)
        AND (status = 'LOAN_REPAYMENT') = (loan_id IS NOT NULL));

ALTER TABLE lending.repayment DROP CONSTRAINT repayment_channel_check;
ALTER TABLE lending.repayment ADD CONSTRAINT repayment_channel_check
    CHECK (channel IN ('MPESA_C2B', 'MPESA_STK', 'BANK', 'TELLER', 'SWITCH', 'INTERNAL', 'SUSPENSE'));

CREATE TABLE payments.suspense_resolution (
    tenant_id          uuid         NOT NULL,
    resolution_id      uuid         NOT NULL,
    payment_id         uuid         NOT NULL,
    target_type        varchar(16)  NOT NULL CHECK (target_type IN ('DEPOSIT_ACCOUNT', 'LOAN')),
    target_reference   varchar(40)  NOT NULL,
    reason             varchar(300) NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by       varchar(100) NOT NULL,
    requested_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    journal_entry_id   uuid,
    PRIMARY KEY (tenant_id, resolution_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES payments.provider_payment (tenant_id, payment_id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (journal_entry_id IS NOT NULL))
);
CREATE UNIQUE INDEX suspense_resolution_one_live ON payments.suspense_resolution (tenant_id, payment_id)
    WHERE status IN ('PENDING', 'APPROVED');

CREATE FUNCTION payments.guard_suspense_resolution() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SUSPENSE_RESOLUTION_IMMUTABLE: resolutions are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'SUSPENSE_RESOLUTION_STATUS: resolutions start PENDING' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT status FROM payments.provider_payment
             WHERE tenant_id = NEW.tenant_id AND payment_id = NEW.payment_id) <> 'UNMATCHED' THEN
            RAISE EXCEPTION 'SUSPENSE_NOT_UNMATCHED: only unmatched receipts can be resolved' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'SUSPENSE_RESOLUTION_FINAL: resolution is already %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'journal_entry_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'journal_entry_id') THEN
        RAISE EXCEPTION 'SUSPENSE_RESOLUTION_IMMUTABLE: only the decision may be recorded' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decided_by IS NULL OR NEW.decided_by = OLD.requested_by THEN
        RAISE EXCEPTION 'SUSPENSE_MAKER_CHECKER: the decision needs a person other than the requester'
            USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER suspense_resolution_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payments.suspense_resolution
    FOR EACH ROW EXECUTE FUNCTION payments.guard_suspense_resolution();

ALTER TABLE payments.suspense_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.suspense_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.suspense_resolution
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON payments.suspense_resolution TO mfi_app;

-- Open suspense items: unmatched receipts without an approved resolution.
CREATE VIEW payments.open_suspense_item WITH (security_invoker = true) AS
SELECT p.tenant_id, p.payment_id, p.provider, p.provider_ref, p.short_code, p.amount, p.currency, p.bill_ref,
       p.msisdn_masked, p.provider_time, p.received_at,
       (current_date - p.received_at::date) AS age_days,
       r.resolution_id AS pending_resolution_id
  FROM payments.provider_payment p
  LEFT JOIN payments.suspense_resolution r
         ON r.tenant_id = p.tenant_id AND r.payment_id = p.payment_id AND r.status = 'PENDING'
 WHERE p.status = 'UNMATCHED'
   AND NOT EXISTS (SELECT 1 FROM payments.suspense_resolution a
                    WHERE a.tenant_id = p.tenant_id AND a.payment_id = p.payment_id AND a.status = 'APPROVED');
GRANT SELECT ON payments.open_suspense_item TO mfi_app;
