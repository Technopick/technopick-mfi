-- =============================================================================
-- V10: M-Pesa STK Push (Lipa na M-Pesa Online) requests.
--   K1  one M-Pesa receipt is applied at most once per tenant, whichever channel reports it
--       (STK result callback or C2B confirmation for the same receipt)
--   K2  a staff request is idempotent per tenant + idempotency key; the amount is whole
--       shillings (Daraja STK amounts are integers) and the phone number is a Kenyan MSISDN
--   K3  request lifecycle only moves forward:
--         PENDING -> DISPATCHED -> SUBMITTED -> COMPLETED | FAILED
--         DISPATCHED -> COMPLETED | FAILED            (result before the submit report)
--         FAILED -> COMPLETED                        (a success result always wins: money moved)
--       COMPLETED is final and always carries its provider payment; FAILED carries a result code
--   K4  request details never change after insert; the full phone number is erased once the
--       request is final (data minimisation), leaving only the masked number
--   K5  the provider's CheckoutRequestID, once recorded, never changes
-- =============================================================================
ALTER TABLE payments.provider_payment DROP CONSTRAINT provider_payment_provider_check;
ALTER TABLE payments.provider_payment
    ADD CONSTRAINT provider_payment_provider_check CHECK (provider IN ('MPESA_C2B', 'MPESA_STK'));
-- K1: M-Pesa receipt numbers are unique across channels.
CREATE UNIQUE INDEX provider_payment_mpesa_receipt_once ON payments.provider_payment (tenant_id, provider_ref)
    WHERE provider IN ('MPESA_C2B', 'MPESA_STK');

CREATE TABLE payments.stk_request (
    tenant_id           uuid          NOT NULL,
    request_id          uuid          NOT NULL,
    idempotency_key     varchar(80)   NOT NULL,
    short_code          varchar(12)   NOT NULL,
    target_reference    varchar(12)   NOT NULL CHECK (target_reference ~ '^[A-Z0-9-]{1,12}$'),
    amount              numeric(19,4) NOT NULL CHECK (amount >= 1 AND amount = trunc(amount)),
    currency            char(3)       NOT NULL,
    msisdn              varchar(12)   CHECK (msisdn ~ '^254[17][0-9]{8}$'),
    msisdn_masked       varchar(20)   NOT NULL,
    description         varchar(13)   NOT NULL,
    status              varchar(16)   NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'DISPATCHED', 'SUBMITTED', 'COMPLETED', 'FAILED')),
    merchant_request_id varchar(60),
    checkout_request_id varchar(60),
    result_code         varchar(20),
    result_desc         varchar(200),
    payment_id          uuid,
    requested_by        varchar(100)  NOT NULL,
    requested_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    updated_at          timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT stk_request_idempotent UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT stk_request_checkout_once UNIQUE (tenant_id, checkout_request_id),
    FOREIGN KEY (tenant_id, short_code) REFERENCES payments.mpesa_paybill (tenant_id, short_code),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES payments.provider_payment (tenant_id, payment_id),
    CHECK ((status = 'COMPLETED') = (payment_id IS NOT NULL)),
    CHECK (status <> 'FAILED' OR result_code IS NOT NULL),
    CHECK (status IN ('PENDING', 'DISPATCHED', 'SUBMITTED') OR msisdn IS NULL),
    CHECK (status NOT IN ('PENDING', 'DISPATCHED', 'SUBMITTED') OR msisdn IS NOT NULL)
);
CREATE INDEX stk_request_open_idx ON payments.stk_request (tenant_id, requested_at)
    WHERE status IN ('PENDING', 'DISPATCHED', 'SUBMITTED');

CREATE FUNCTION payments.guard_stk_request() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: STK requests are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'merchant_request_id' - 'checkout_request_id' - 'result_code' - 'result_desc'
            - 'payment_id' - 'updated_at' - 'msisdn')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'merchant_request_id' - 'checkout_request_id' - 'result_code'
            - 'result_desc' - 'payment_id' - 'updated_at' - 'msisdn') THEN
        RAISE EXCEPTION 'STK_REQUEST_IMMUTABLE: request details never change' USING ERRCODE = 'P0001';
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
           (OLD.status = 'PENDING'    AND NEW.status = 'DISPATCHED')
        OR (OLD.status = 'DISPATCHED' AND NEW.status IN ('SUBMITTED', 'COMPLETED', 'FAILED'))
        OR (OLD.status = 'SUBMITTED'  AND NEW.status IN ('COMPLETED', 'FAILED'))
        OR (OLD.status = 'FAILED'     AND NEW.status = 'COMPLETED')) THEN
        RAISE EXCEPTION 'STK_REQUEST_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'FAILED' AND NEW.status = 'FAILED' THEN
        RAISE EXCEPTION 'STK_REQUEST_FINAL: request % already failed', OLD.request_id USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER stk_request_guard BEFORE UPDATE OR DELETE ON payments.stk_request
    FOR EACH ROW EXECUTE FUNCTION payments.guard_stk_request();

ALTER TABLE payments.stk_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.stk_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.stk_request
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON payments.stk_request TO mfi_app;

-- The callback URL carries our request id, so a result can be matched even if the integration
-- runtime stopped before it reported the CheckoutRequestID to core.
ALTER TABLE integration.provider_callback ADD COLUMN route_ref varchar(60);
