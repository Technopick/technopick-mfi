-- =============================================================================
-- V11: STK Push reconciliation.
--   R1  a request nobody dispatched may be expired (PENDING -> FAILED 'EXPIRED'): no prompt was sent
--   R2  requests needing a person are flagged (attention); final requests carry no flag
--   R3  status checks are counted; only the reconciliation fields may change besides the lifecycle
-- =============================================================================
ALTER TABLE payments.stk_request
    ADD COLUMN attention varchar(32)
        CHECK (attention IN ('SEND_OUTCOME_UNKNOWN', 'PAID_AWAITING_RECEIPT', 'RECONCILE_EXHAUSTED')),
    ADD COLUMN check_count int NOT NULL DEFAULT 0 CHECK (check_count >= 0),
    ADD COLUMN last_checked_at timestamptz,
    ADD CONSTRAINT stk_request_final_has_no_attention CHECK (status NOT IN ('COMPLETED', 'FAILED') OR attention IS NULL),
    ADD CONSTRAINT stk_request_expiry_code CHECK (result_code IS DISTINCT FROM 'EXPIRED' OR checkout_request_id IS NULL);
CREATE INDEX stk_request_attention_idx ON payments.stk_request (tenant_id, requested_at) WHERE attention IS NOT NULL;

CREATE OR REPLACE FUNCTION payments.guard_stk_request() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: STK requests are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'merchant_request_id' - 'checkout_request_id' - 'result_code' - 'result_desc'
            - 'payment_id' - 'updated_at' - 'msisdn' - 'attention' - 'check_count' - 'last_checked_at')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'merchant_request_id' - 'checkout_request_id' - 'result_code'
            - 'result_desc' - 'payment_id' - 'updated_at' - 'msisdn' - 'attention' - 'check_count' - 'last_checked_at') THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: request details never change' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.check_count < OLD.check_count THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: check count never decreases' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.msisdn IS NOT NULL AND NEW.msisdn IS DISTINCT FROM OLD.msisdn THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: the phone number can only be erased' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.checkout_request_id IS NOT NULL AND NEW.checkout_request_id IS DISTINCT FROM OLD.checkout_request_id THEN
        RAISE EXCEPTION 'STK_CHECKOUT_FIXED: CheckoutRequestID already recorded' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.merchant_request_id IS NOT NULL AND NEW.merchant_request_id IS DISTINCT FROM OLD.merchant_request_id THEN
        RAISE EXCEPTION 'STK_CHECKOUT_FIXED: MerchantRequestID already recorded' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION 'STK_REQUEST_FINAL: request % is completed', OLD.request_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING'    AND NEW.status IN ('DISPATCHED', 'FAILED'))
        OR (OLD.status = 'DISPATCHED' AND NEW.status IN ('SUBMITTED', 'COMPLETED', 'FAILED'))
        OR (OLD.status = 'SUBMITTED'  AND NEW.status IN ('COMPLETED', 'FAILED'))
        OR (OLD.status = 'FAILED'     AND NEW.status = 'COMPLETED')) THEN
        RAISE EXCEPTION 'STK_REQUEST_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'FAILED' AND NEW.result_code IS DISTINCT FROM 'EXPIRED' THEN
        RAISE EXCEPTION 'STK_REQUEST_STATUS: an undispatched request can only expire' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'FAILED' AND NEW.status = 'FAILED' THEN
        RAISE EXCEPTION 'STK_REQUEST_FINAL: request % already failed', OLD.request_id USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
