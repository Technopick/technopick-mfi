package ke.co.technopick.mfi.core.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.AccountKind;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.Category;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.Parsed;
import org.junit.jupiter.api.Test;

/** The statement layout below is TEST-ONLY sample data in the portal's column layout (ADR-0038). */
class MpesaStatementTest {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final LocalDate DAY = LocalDate.of(2026, 11, 27);

    static final String PAYBILL = """
            Account Holder:,TEST ONLY SACCO
            Short Code:,600993
            Time Period:,27-11-2026 - 27-11-2026

            Receipt No.,Completion Time,Initiation Time,Details,Transaction Status,Paid In,Withdrawn,Balance,Balance Confirmed,Reason Type,Other Party Info,Linked Transaction ID,A/C No.
            TKR0000001,2026-11-27 08:01:10,2026-11-27 08:01:09,"Pay Bill from 254712345678 - JOHN DOE Acc. LN-000001",Completed,"1,000.00",,"11,000.00",true,Pay Bill,254712345678 - JOHN DOE,,LN-000001
            TKR0000001,2026-11-27 08:01:10,2026-11-27 08:01:09,Pay Bill Charge,Completed,,-15.00,"10,985.00",true,Pay Bill Charge,,,
            TKR0000002,2026-11-27 09:30:00,2026-11-27 09:29:58,"Pay Bill Online from 0722000111 - MARY ""M"" WANJIKU Acc. 0100000001",Completed,500.00,,"11,485.00",true,Pay Bill Online,0722000111 - MARY WANJIKU,,0100000001
            TKR0000003,2026-11-27 10:00:00,,Pay Bill from 254712000999,Failed,300.00,,,false,Pay Bill,,,
            TKR0000004,2026-11-27 17:00:00,,Organization Settle To Bank Account,Completed,,"10,000.00","1,485.00",true,Funds Transfer,,,
            """;

    @Test
    void readsAPaybillDayAndDerivesTheBalances() {
        Parsed p = MpesaStatement.parse(PAYBILL, AccountKind.PAYBILL, "600993", DAY, NAIROBI);
        assertEquals(5, p.lines().size());
        assertEquals(new BigDecimal("1500.00"), p.totalPaidIn());
        assertEquals(new BigDecimal("10015.00"), p.totalWithdrawn());
        assertEquals(new BigDecimal("1485.00"), p.closingBalance());
        assertEquals(new BigDecimal("10000.00"), p.openingBalance());
        assertTrue(p.balanceChainOk());

        var receipt = p.lines().get(0);
        assertEquals(Category.RECEIPT, receipt.category());
        assertEquals("Pay Bill from 2547****678 Acc. LN-000001", receipt.details());
        assertEquals("2547****678", receipt.otherPartyMasked());
        assertEquals("LN-000001", receipt.accountRef());
        assertEquals(Category.CHARGE, p.lines().get(1).category());
        assertEquals(new BigDecimal("15.00"), p.lines().get(1).withdrawn());
        // the account reference after "Acc." is kept; the name after the phone is dropped
        assertEquals("Pay Bill Online from 0722****111 Acc. 0100000001", p.lines().get(2).details());
        assertFalse(p.lines().get(3).completed());
        assertEquals(Category.TRANSFER, p.lines().get(4).category());
    }

    @Test
    void newestFirstFilesGiveTheSameBalances() {
        String header = PAYBILL.substring(0, PAYBILL.indexOf("TKR0000001"));
        String[] rows = PAYBILL.substring(header.length()).strip().split("\n");
        StringBuilder reversed = new StringBuilder(header);
        for (int i = rows.length - 1; i >= 0; i--) {
            reversed.append(rows[i]).append("\r\n");
        }
        Parsed p = MpesaStatement.parse(reversed.toString(), AccountKind.PAYBILL, "600993", DAY, NAIROBI);
        assertEquals(new BigDecimal("1485.00"), p.closingBalance());
        assertEquals(new BigDecimal("10000.00"), p.openingBalance());
        assertTrue(p.balanceChainOk());
    }

    @Test
    void b2cPaymentsChargesAndFunding() {
        String csv = """
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance,Reason Type,Other Party Info
                TKB0000001,27/11/2026 07:00:00,Business Funds Received,Completed,50000.00,,50000.00,Organization Funds Received,
                TKB0000002,27/11/2026 11:00:00,Business Payment to 254711222333 - PETER K,Completed,,9800.00,40200.00,Business Payment to Customer via API,254711222333 - PETER K
                TKB0000002,27/11/2026 11:00:00,Business Payment to Customer Charge,Completed,,33.00,40167.00,Business Payment to Customer Charge,
                """;
        Parsed p = MpesaStatement.parse(csv, AccountKind.B2C, "600000", DAY, NAIROBI);
        assertEquals(Category.TRANSFER, p.lines().get(0).category());
        assertEquals(Category.PAYMENT, p.lines().get(1).category());
        assertEquals("Business Payment to 2547****333", p.lines().get(1).details());
        assertEquals(Category.CHARGE, p.lines().get(2).category());
        assertEquals(new BigDecimal("0.00"), p.openingBalance());
        assertEquals(new BigDecimal("40167.00"), p.closingBalance());
    }

    @Test
    void outOfOrderBalancesAreFlaggedNotRejected() {
        String csv = """
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                TKR0000001,2026-11-27 08:00:00,Pay Bill,Completed,100.00,,600.00
                TKR0000002,2026-11-27 09:00:00,Pay Bill,Completed,100.00,,650.00
                """;
        Parsed p = MpesaStatement.parse(csv, AccountKind.PAYBILL, "600993", DAY, NAIROBI);
        assertFalse(p.balanceChainOk());
        assertEquals(new BigDecimal("650.00"), p.closingBalance());
        assertEquals(new BigDecimal("450.00"), p.openingBalance());
    }

    @Test
    void rejectsFilesThatAreNotOneDayOfThisFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> MpesaStatement.parse("a,b\n1,2\n", AccountKind.PAYBILL, "600993", DAY, NAIROBI));
        assertThrows(IllegalArgumentException.class, () -> MpesaStatement.parse("""
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                TKR0000001,2026-11-28 08:00:00,Pay Bill,Completed,100.00,,600.00
                """, AccountKind.PAYBILL, "600993", DAY, NAIROBI));
        assertThrows(IllegalArgumentException.class, () -> MpesaStatement.parse("""
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                TKR0000001,2026-11-27 08:00:00,Pay Bill,Completed,100.005,,600.00
                """, AccountKind.PAYBILL, "600993", DAY, NAIROBI));
        assertThrows(IllegalArgumentException.class, () -> MpesaStatement.parse("""
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                bad,2026-11-27 08:00:00,Pay Bill,Completed,100.00,,600.00
                """, AccountKind.PAYBILL, "600993", DAY, NAIROBI));
        assertThrows(IllegalArgumentException.class, () -> MpesaStatement.parse("""
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                TKR0000001,2026-11-27 08:00:00,Pay Bill,Completed,100.00,,
                """, AccountKind.PAYBILL, "600993", DAY, NAIROBI));
        assertThrows(IllegalArgumentException.class, () -> MpesaStatement.parse("""
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                TKR0000001,2026-11-27 08:00:00,"Pay Bill,Completed,100.00,,600.00
                """, AccountKind.PAYBILL, "600993", DAY, NAIROBI));
    }

    @Test
    void refusesTheStatementOfAnotherShortCode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MpesaStatement.parse(PAYBILL, AccountKind.B2C, "600000", DAY, NAIROBI));
        assertEquals("the file is the statement of short code 600993, not 600000", e.getMessage());
    }

    @Test
    void masksPhoneNumbersOnly() {
        assertEquals("2547****678", MpesaStatement.maskParty("254712345678 - JOHN DOE"));
        assertEquals("2547****678", MpesaStatement.maskParty("2547******678 - JOHN DOE"));
        assertNull(MpesaStatement.maskParty("SAFARICOM"));
        assertEquals("Pay Bill Charge", MpesaStatement.maskDetails("Pay Bill Charge"));
    }
}
