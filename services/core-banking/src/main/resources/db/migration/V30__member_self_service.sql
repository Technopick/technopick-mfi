-- =============================================================================
-- V30 (increment 33, ADR-0033): member self-service.
--
-- An M-Pesa STK prompt asked for by a member (not by staff) records which member asked. The
-- database then insists that the prompt goes to that member's own registered phone, and that the
-- money goes to that member's own savings account or loan, so even a fault in the application
-- cannot prompt someone else's phone or pay into someone else's account from a member session.
-- =============================================================================
ALTER TABLE payments.stk_request ADD COLUMN member_customer_id uuid;
ALTER TABLE payments.stk_request
    ADD FOREIGN KEY (tenant_id, member_customer_id) REFERENCES customer.customer (tenant_id, customer_id);
CREATE INDEX stk_request_member_recent ON payments.stk_request (tenant_id, member_customer_id, requested_at)
    WHERE member_customer_id IS NOT NULL;

CREATE FUNCTION payments.check_member_stk() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c customer.customer%ROWTYPE;
BEGIN
    IF NEW.member_customer_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO c FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.member_customer_id;
    IF c.status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'MEMBER_NOT_ACTIVE: only an active member can ask for a payment prompt' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.msisdn IS DISTINCT FROM c.msisdn THEN
        RAISE EXCEPTION 'MEMBER_PHONE_MISMATCH: a member''s payment prompt goes only to the member''s registered phone'
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM savings.deposit_account a
                    WHERE a.tenant_id = NEW.tenant_id AND a.customer_id = c.customer_id AND a.account_number = NEW.target_reference)
       AND NOT EXISTS (SELECT 1 FROM lending.loan l
                        WHERE l.tenant_id = NEW.tenant_id AND l.customer_id = c.customer_id AND l.loan_number = NEW.target_reference) THEN
        RAISE EXCEPTION 'MEMBER_TARGET_NOT_OWN: % is not the member''s own account or loan', NEW.target_reference
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER stk_request_member BEFORE INSERT ON payments.stk_request
    FOR EACH ROW EXECUTE FUNCTION payments.check_member_stk();

CREATE FUNCTION payments.guard_member_stk_link() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.member_customer_id IS DISTINCT FROM OLD.member_customer_id THEN
        RAISE EXCEPTION 'MEMBER_LINK_IMMUTABLE: who asked for a payment prompt is never changed' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER stk_request_member_link BEFORE UPDATE ON payments.stk_request
    FOR EACH ROW EXECUTE FUNCTION payments.guard_member_stk_link();
