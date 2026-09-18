package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)");

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    // Where the balance/limit clause starts, if it appears at all. The
    // transaction amount is only ever looked for before this point - see
    // INC-2026-09-11: a whole-rupee amount (no decimal, e.g. "Rs.5") was
    // falling through the old decimal-only AMOUNT pattern, so find() carried
    // on scanning and locked onto the *next* Rs/INR figure with two decimals,
    // which was always the stated balance. Anchoring the search to the text
    // before "Avl Bal" etc. makes that impossible regardless of whether the
    // amount itself has a decimal point.
    private static final Pattern BALANCE_MARKER = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure before any stated balance/limit. */
    public static BigDecimal first(String body) {
        Matcher marker = BALANCE_MARKER.matcher(body);
        String searchIn = marker.find() ? body.substring(0, marker.start()) : body;
        Matcher m = AMOUNT.matcher(searchIn);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
