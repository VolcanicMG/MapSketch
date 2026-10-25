package com.volcanicmg.mapsketch.model;

/**
 * Fractional z-order keys, the scheme Excalidraw uses (port of rocicorp's fractional-indexing): a variable-length
 * base-62 integer part whose first character encodes its length ({@code a}-{@code z} positive, {@code A}-{@code Z}
 * negative), then a fraction that never ends in {@code 0}. Appending or prepending only steps the integer, so keys
 * stay a few characters long; only repeated inserts at the same spot grow the fraction.
 */
public final class FracIndex {
    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = '0', LAST = 'z';
    private static final String SMALLEST_INTEGER = "A" + "0".repeat(26), INTEGER_ZERO = "a0";

    private FracIndex() {
    }

    /**
     * @param a lower key, or null for "before everything"
     * @param b upper key, or null for "after everything"; must be greater than {@code a}
     * @return a key strictly between {@code a} and {@code b}
     * @throws IllegalArgumentException on malformed keys or {@code a >= b}
     */
    public static String between(String a, String b) {
        if (a != null) validate(a);
        if (b != null) validate(b);
        if (a != null && b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        if (a == null) {
            if (b == null) return INTEGER_ZERO;
            String ib = integerPart(b), fb = b.substring(ib.length());
            if (ib.equals(SMALLEST_INTEGER)) return ib + midpoint("", fb);
            if (ib.compareTo(b) < 0) return ib;
            String res = decrement(ib);
            if (res == null) throw new IllegalArgumentException("cannot go below " + b);
            return res;
        }
        String ia = integerPart(a), fa = a.substring(ia.length());
        if (b == null) {
            String i = increment(ia);
            return i == null ? ia + midpoint(fa, null) : i;
        }
        String ib = integerPart(b), fb = b.substring(ib.length());
        if (ia.equals(ib)) return ia + midpoint(fa, fb);
        String i = increment(ia);
        if (i == null) throw new IllegalArgumentException("cannot go above " + a);
        return i.compareTo(b) < 0 ? i : ia + midpoint(fa, null);
    }

    /** @return true if {@code key} is a well-formed index of at most {@link ElementCodec#MAX_ORDER} chars */
    public static boolean valid(String key) {
        if (key == null || key.isEmpty() || key.length() > ElementCodec.MAX_ORDER) return false;
        try {
            validate(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Fraction strictly between {@code a} and {@code b} (null = 1); neither may end in the zero digit. */
    private static String midpoint(String a, String b) {
        if (b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        if (!a.isEmpty() && a.charAt(a.length() - 1) == ZERO || b != null && !b.isEmpty() && b.charAt(b.length() - 1) == ZERO)
            throw new IllegalArgumentException("trailing zero");
        if (b != null) {
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : ZERO) == b.charAt(n)) n++;
            if (n > 0) return b.substring(0, n) + midpoint(a.length() > n ? a.substring(n) : "", b.substring(n));
        }
        int da = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int db = b != null ? DIGITS.indexOf(b.charAt(0)) : DIGITS.length();
        if (db - da > 1) return String.valueOf(DIGITS.charAt((int) Math.round(0.5 * (da + db))));
        if (b != null && b.length() > 1) return b.substring(0, 1);
        return DIGITS.charAt(da) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    private static int integerLength(char head) {
        if (head >= 'a' && head <= 'z') return head - 'a' + 2;
        if (head >= 'A' && head <= 'Z') return 'Z' - head + 2;
        throw new IllegalArgumentException("bad head " + head);
    }

    private static String integerPart(String key) {
        int n = integerLength(key.charAt(0));
        if (n > key.length()) throw new IllegalArgumentException("short key " + key);
        return key.substring(0, n);
    }

    private static void validate(String key) {
        if (key.equals(SMALLEST_INTEGER)) throw new IllegalArgumentException("smallest key");
        String i = integerPart(key);
        for (int k = 0; k < key.length(); k++)
            if (k > 0 && DIGITS.indexOf(key.charAt(k)) < 0) throw new IllegalArgumentException("bad digit");
        String f = key.substring(i.length());
        if (!f.isEmpty() && f.charAt(f.length() - 1) == ZERO) throw new IllegalArgumentException("trailing zero");
    }

    private static String increment(String x) {
        char head = x.charAt(0);
        char[] d = x.substring(1).toCharArray();
        boolean carry = true;
        for (int i = d.length - 1; carry && i >= 0; i--) {
            int v = DIGITS.indexOf(d[i]) + 1;
            if (v == DIGITS.length()) d[i] = ZERO;
            else {
                d[i] = DIGITS.charAt(v);
                carry = false;
            }
        }
        if (!carry) return head + new String(d);
        if (head == 'Z') return "a" + ZERO;
        if (head == 'z') return null;
        char h = (char) (head + 1);
        String digits = new String(d);
        return h > 'a' ? h + digits + ZERO : h + digits.substring(0, digits.length() - 1);
    }

    private static String decrement(String x) {
        char head = x.charAt(0);
        char[] d = x.substring(1).toCharArray();
        boolean borrow = true;
        for (int i = d.length - 1; borrow && i >= 0; i--) {
            int v = DIGITS.indexOf(d[i]) - 1;
            if (v == -1) d[i] = LAST;
            else {
                d[i] = DIGITS.charAt(v);
                borrow = false;
            }
        }
        if (!borrow) return head + new String(d);
        if (head == 'a') return "Z" + LAST;
        if (head == 'A') return null;
        char h = (char) (head - 1);
        String digits = new String(d);
        return h < 'Z' ? h + digits + LAST : h + digits.substring(0, digits.length() - 1);
    }
}
