package com.cms.card;

/** ISO/IEC 7812 Luhn (mod 10) check digit. */
public final class Luhn {

    private Luhn() {}

    /** Check digit to append to the given digits (PAN without its last digit). */
    public static char checkDigit(String digitsWithoutCheck) {
        int sum = 0;
        boolean dbl = true; // rightmost payload digit is doubled
        for (int i = digitsWithoutCheck.length() - 1; i >= 0; i--) {
            int d = digitsWithoutCheck.charAt(i) - '0';
            if (d < 0 || d > 9) throw new IllegalArgumentException("non-digit in PAN");
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    public static boolean isValid(String pan) {
        if (pan == null || pan.length() < 2) return false;
        return checkDigit(pan.substring(0, pan.length() - 1)) == pan.charAt(pan.length() - 1);
    }
}
