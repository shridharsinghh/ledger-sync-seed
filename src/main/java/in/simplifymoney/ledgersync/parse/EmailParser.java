package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Shape (both HDFC and ICICI senders use it):
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been debited with INR 99.99.
 *   Merchant / Remarks: IRCTC
 *   Transaction reference: 8085121323
 *
 *   This is a system generated email.
 *
 * The Date header carries the same instant as the "On:"/"on" time in the
 * matching bank SMS for this same underlying transaction - it is used as
 * occurred_at, not received_at (received_at is when the mail server got it).
 *
 * These emails never quote an account balance, so statedBalance is always
 * null here.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern BODY = Pattern.compile(
            "account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with "
                    + "(?<amount>(?:Rs\\.?|INR)\\s*[0-9,]+(?:\\.[0-9]{2})?)\\.\\s*"
                    + "Merchant\\s*/\\s*Remarks:\\s*(?<merchant>.+)");

    private static final Pattern DATE_HEADER = Pattern.compile("^Date:\\s*(.+)$", Pattern.MULTILINE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher b = BODY.matcher(body);
        if (!b.find()) return Optional.empty();

        Matcher d = DATE_HEADER.matcher(body);
        if (!d.find()) return Optional.empty();
        OffsetDateTime at = parseHeaderDate(d.group(1).trim());
        if (at == null) return Optional.empty();

        Direction dir = "debited".equals(b.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        var amount = Amounts.first(b.group("amount"));
        if (amount == null) return Optional.empty();

        return Optional.of(new ParsedTxn(b.group("acct"), at, dir, amount,
                b.group("merchant").trim(), null, m.messageId()));
    }

    private OffsetDateTime parseHeaderDate(String raw) {
        try {
            // The header's own offset isn't always +0530 (one message in the
            // corpus uses +0000/UTC for what is otherwise the same
            // transaction as its SMS counterpart) - normalize to IST so
            // dedup keys match regardless of which offset the header used.
            return OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withOffsetSameInstant(Dates.IST);
        } catch (Exception e) {
            return null;
        }
    }
}
