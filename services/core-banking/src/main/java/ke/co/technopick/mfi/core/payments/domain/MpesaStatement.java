package ke.co.technopick.mfi.core.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An M-Pesa organisation statement as exported from the M-Pesa business portal (CSV), read for one
 * calendar day of one short code (ADR-0038).
 *
 * <p>The table starts at the row whose first cells name the columns ("Receipt No.", "Completion
 * Time", ...); rows above it (account holder, short code, period) are skipped. Columns are found
 * by name, so their order does not matter. Withdrawn amounts may be written as negative numbers.
 *
 * <p>Other parties' names are not kept: phone numbers are masked and any name that follows them
 * is dropped, in both the "Other Party Info" and "Details" columns.
 */
public final class MpesaStatement {

    public enum AccountKind { PAYBILL, B2C }

    public enum Category { RECEIPT, PAYMENT, CHARGE, TRANSFER, FUNDING, REVERSAL, OTHER }

    public record Line(int lineNo, String receiptNo, OffsetDateTime completedAt, OffsetDateTime initiatedAt, String details,
                       String status, BigDecimal paidIn, BigDecimal withdrawn, BigDecimal balance, String reasonType,
                       String otherPartyMasked, String linkedTxn, String accountRef, Category category) {
        public boolean completed() {
            return "Completed".equals(status);
        }

        /** What the line did to the account: paid in less withdrawn. */
        public BigDecimal net() {
            return paidIn.subtract(withdrawn);
        }
    }

    public record Parsed(List<Line> lines, BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal totalPaidIn,
                         BigDecimal totalWithdrawn, boolean balanceChainOk) { }

    public static final int MAX_LINES = 20_000;

    private static final Pattern RECEIPT = Pattern.compile("^[A-Z0-9]{8,20}$");
    /** A Kenyan mobile number, possibly already partly masked, that is not an account reference. */
    private static final String PHONE_TEXT = "(?<![\\dA-Za-z])(?<!Acc\\.\\s)((?:254|0)[17][\\d*]{5,7}\\d{3})(?!\\d)";
    private static final Pattern PHONE_AND_NAME = Pattern.compile(PHONE_TEXT + "(\\s*-\\s*[^,]*?)?(?=\\s+Acc\\.|\\s*$)");
    private static final Pattern PHONE = Pattern.compile(PHONE_TEXT);
    private static final List<DateTimeFormatter> TIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

    private MpesaStatement() { }

    /**
     * Reads a statement. Every line must be on {@code statementDate} in {@code zone}; at least one
     * line must be completed, and completed lines need a balance.
     *
     * @throws IllegalArgumentException naming the row and the problem when the file cannot be read
     */
    public static Parsed parse(String csv, AccountKind kind, LocalDate statementDate, ZoneId zone) {
        List<List<String>> rows = rows(csv);
        int headerRow = -1;
        for (int i = 0; i < Math.min(rows.size(), 40) && headerRow < 0; i++) {
            if (rows.get(i).stream().map(MpesaStatement::key).anyMatch(k -> k.equals("receiptno") || k.equals("receiptnumber"))) {
                headerRow = i;
            }
        }
        if (headerRow < 0) {
            throw new IllegalArgumentException("not an M-Pesa statement: no \"Receipt No.\" column header in the first 40 rows");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(headerRow);
        for (int c = 0; c < header.size(); c++) {
            col.putIfAbsent(alias(key(header.get(c))), c);
        }
        for (String required : List.of("receiptno", "completiontime", "details", "transactionstatus", "paidin", "withdrawn")) {
            if (!col.containsKey(required)) {
                throw new IllegalArgumentException("the statement has no \"" + required + "\" column");
            }
        }
        List<Line> lines = new ArrayList<>();
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(String::isBlank)) {
                continue;
            }
            int rowNo = i + 1;
            if (lines.size() == MAX_LINES) {
                throw new IllegalArgumentException("more than " + MAX_LINES + " lines; upload one day per file");
            }
            String receipt = cell(r, col, "receiptno").toUpperCase(Locale.ROOT);
            if (!RECEIPT.matcher(receipt).matches()) {
                throw new IllegalArgumentException("row " + rowNo + ": receipt \"" + receipt + "\" is not an M-Pesa receipt number");
            }
            OffsetDateTime completed = time(cell(r, col, "completiontime"), zone, rowNo, true);
            if (!completed.atZoneSameInstant(zone).toLocalDate().equals(statementDate)) {
                throw new IllegalArgumentException("row " + rowNo + ": completed on "
                        + completed.atZoneSameInstant(zone).toLocalDate() + ", not on the statement date " + statementDate);
            }
            OffsetDateTime initiated = col.containsKey("initiationtime")
                    ? time(cell(r, col, "initiationtime"), zone, rowNo, false) : null;
            String status = normaliseStatus(cell(r, col, "transactionstatus"));
            BigDecimal paidIn = amount(cell(r, col, "paidin"), rowNo, "Paid In");
            BigDecimal withdrawn = amount(cell(r, col, "withdrawn"), rowNo, "Withdrawn").abs();
            if (paidIn.signum() < 0) {
                throw new IllegalArgumentException("row " + rowNo + ": Paid In is negative");
            }
            if (paidIn.signum() > 0 && withdrawn.signum() > 0) {
                throw new IllegalArgumentException("row " + rowNo + ": both Paid In and Withdrawn are set");
            }
            String balanceText = col.containsKey("balance") ? cell(r, col, "balance") : "";
            BigDecimal balance = balanceText.isBlank() ? null : amount(balanceText, rowNo, "Balance");
            if ("Completed".equals(status) && paidIn.signum() == 0 && withdrawn.signum() == 0) {
                throw new IllegalArgumentException("row " + rowNo + ": a completed line moves no money");
            }
            String details = limit(maskDetails(cell(r, col, "details")), 200);
            String reason = col.containsKey("reasontype") ? emptyToNull(limit(cell(r, col, "reasontype"), 80)) : null;
            String other = col.containsKey("otherpartyinfo") ? maskParty(cell(r, col, "otherpartyinfo")) : null;
            String linked = col.containsKey("linkedtransactionid")
                    ? emptyToNull(limit(cell(r, col, "linkedtransactionid").toUpperCase(Locale.ROOT), 20)) : null;
            String accountRef = col.containsKey("acno") ? emptyToNull(limit(cell(r, col, "acno"), 40)) : null;
            Category category = categorise(kind, details, reason, paidIn, withdrawn);
            lines.add(new Line(lines.size() + 1, receipt, completed, initiated, details, status, paidIn, withdrawn, balance,
                    reason, other, linked, accountRef, category));
        }
        List<Line> completedLines = lines.stream().filter(Line::completed).toList();
        if (completedLines.isEmpty()) {
            throw new IllegalArgumentException("the statement has no completed transactions");
        }
        for (Line l : completedLines) {
            if (l.balance() == null) {
                throw new IllegalArgumentException("line " + l.lineNo() + ": a completed line needs its Balance");
            }
        }
        BigDecimal paidIn = completedLines.stream().map(Line::paidIn).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal withdrawn = completedLines.stream().map(Line::withdrawn).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean ascending = chain(completedLines);
        boolean descending = chain(completedLines.reversed());
        Line last;
        if (ascending) {
            last = completedLines.getLast();
        } else if (descending) {
            last = completedLines.getFirst();
        } else {
            last = completedLines.stream().max(Comparator.comparing(Line::completedAt).thenComparing(Line::lineNo)).orElseThrow();
        }
        BigDecimal closing = last.balance();
        return new Parsed(List.copyOf(lines), closing.subtract(paidIn).add(withdrawn), closing, paidIn, withdrawn,
                ascending || descending);
    }

    /** True when each balance equals the previous one plus the line's movement. */
    private static boolean chain(List<Line> ordered) {
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).balance().compareTo(ordered.get(i - 1).balance().add(ordered.get(i).net())) != 0) {
                return false;
            }
        }
        return true;
    }

    public static Category categorise(AccountKind kind, String details, String reason, BigDecimal paidIn, BigDecimal withdrawn) {
        String text = ((reason == null ? "" : reason) + " " + (details == null ? "" : details)).toLowerCase(Locale.ROOT);
        if (text.contains("revers")) {
            return Category.REVERSAL;
        }
        if (text.contains("charge")) {
            return Category.CHARGE;
        }
        if (text.contains("settle") || text.contains("transfer") || text.contains("funds") || text.contains("to bank")) {
            return Category.TRANSFER;
        }
        if (kind == AccountKind.PAYBILL && paidIn.signum() > 0) {
            return Category.RECEIPT;
        }
        if (kind == AccountKind.B2C && withdrawn.signum() > 0) {
            return Category.PAYMENT;
        }
        if (kind == AccountKind.B2C && paidIn.signum() > 0) {
            return Category.FUNDING;
        }
        return Category.OTHER;
    }

    static String maskParty(String text) {
        Matcher m = PHONE.matcher(text == null ? "" : text);
        return m.find() ? mask(m.group(1)) : null;
    }

    static String maskDetails(String text) {
        if (text == null) {
            return "";
        }
        String masked = PHONE_AND_NAME.matcher(text).replaceAll(m -> Matcher.quoteReplacement(mask(m.group(1))));
        return PHONE.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(mask(m.group(1))));
    }

    private static String mask(String phone) {
        String digits = phone.replace("*", "");
        if (digits.length() < 7) {
            return "****";
        }
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }

    private static String normaliseStatus(String s) {
        String t = s.strip();
        return t.equalsIgnoreCase("completed") ? "Completed" : limit(t.isEmpty() ? "Unknown" : t, 20);
    }

    private static BigDecimal amount(String text, int row, String column) {
        String t = text.strip().replace(",", "").replace(" ", "");
        if (t.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.scale() > 2) {
                throw new IllegalArgumentException("row " + row + ": " + column + " \"" + text + "\" has more than 2 decimals");
            }
            if (v.abs().compareTo(new BigDecimal("1000000000000")) >= 0) {
                throw new IllegalArgumentException("row " + row + ": " + column + " is out of range");
            }
            return v.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("row " + row + ": " + column + " \"" + text + "\" is not an amount", e);
        }
    }

    private static OffsetDateTime time(String text, ZoneId zone, int row, boolean required) {
        String t = text.strip();
        if (t.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("row " + row + ": Completion Time is empty");
            }
            return null;
        }
        for (DateTimeFormatter f : TIMES) {
            try {
                return LocalDateTime.parse(t, f).atZone(zone).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
                // try the next layout
            }
        }
        throw new IllegalArgumentException("row " + row + ": \"" + t + "\" is not a date and time");
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        int i = col.get(name);
        return i < row.size() ? row.get(i).strip() : "";
    }

    private static String key(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String alias(String key) {
        return switch (key) {
            case "receiptnumber" -> "receiptno";
            case "withdrawals", "withdrawal", "paidout" -> "withdrawn";
            case "status" -> "transactionstatus";
            case "accountno", "acnumber" -> "acno";
            default -> key;
        };
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** RFC 4180 CSV: quoted fields may hold commas, doubled quotes and line breaks. */
    static List<List<String>> rows(String csv) {
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("the file ends inside a quoted field");
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
