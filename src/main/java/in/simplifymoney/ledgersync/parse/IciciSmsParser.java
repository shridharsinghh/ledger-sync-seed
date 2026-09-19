package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two shapes appear in the corpus:
 *   V1: "Dear Customer, Acct XX9075 is debited with INR 333.33 on
 *        04/07/2026 07:54. Info: SWIGGY. Avl Bal Rs.49,857.25"
 *   V2: "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41;
 *        UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) (?<dir>Cr|Dr) INR [0-9,]+(?:\\.[0-9]{2})? "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no \\d+\\.");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());
        if (v1.find()) {
            return build(m, v1.group("acct"), v1.group("dir"), v1.group("when"),
                    v1.group("merchant"), "debited".equals(v1.group("dir")));
        }

        Matcher v2 = V2.matcher(m.body());
        if (v2.find()) {
            return build(m, v2.group("acct"), v2.group("dir"), v2.group("when"),
                    v2.group("merchant"), "Dr".equals(v2.group("dir")));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String dirRaw, String when,
            String merchant, boolean isDebit) {
        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(when);
        if (amount == null || at == null) return Optional.empty();

        Direction d = isDebit ? Direction.DEBIT : Direction.CREDIT;
        return Optional.of(new ParsedTxn(acct, at, d, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
