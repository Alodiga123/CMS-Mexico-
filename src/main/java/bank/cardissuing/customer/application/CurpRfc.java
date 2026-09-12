package bank.cardissuing.customer.application;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Structural validation of the two Mexican identifiers a cardholder brings: CURP (18 characters,
 * RENAPO) and RFC of a natural person (13 characters, SAT). Both carry the birth date and a check
 * digit, so a typo or an invented number is caught here before any list is consulted.
 * The algorithms are the public ones; nothing here calls RENAPO or the SAT.
 */
public final class CurpRfc {

    private CurpRfc() { }

    private static final String CURP_ALPHABET = "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
    private static final String RFC_ALPHABET = "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ";
    private static final Set<String> STATES = Set.of("AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG", "GT", "GR", "HG", "JC",
            "MC", "MN", "MS", "NT", "NL", "OC", "PL", "QT", "QR", "SP", "SL", "SR", "TC", "TS", "TL", "VZ", "YN", "ZS", "NE");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    public record Check(boolean ok, String detail) { }

    /** Upper case, no accents, single spaces: how the identifiers spell names. */
    public static String normalize(String s) {
        if (s == null) return "";
        // keep the Ñ (the identifiers know it); drop every other accent
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == 'Ñ' || ch == 'ñ') { sb.append('Ñ'); continue; }
            sb.append(Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD).replaceAll("\\p{M}", ""));
        }
        return sb.toString().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9Ñ& ]", " ").trim().replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------ CURP

    public static Check curp(String raw, LocalDate birthDate, String sex, String paternalSurname, String firstNames) {
        String c = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{4}\\d{6}[HM][A-Z]{2}[B-DF-HJ-NP-TV-Z]{3}[0-9A-Z]\\d")) return new Check(false, "estructura de CURP inválida");
        if (curpCheckDigit(c.substring(0, 17)) != c.charAt(17)) return new Check(false, "dígito verificador de CURP incorrecto");
        if (birthDate != null && !c.substring(4, 10).equals(birthDate.format(YYMMDD))) return new Check(false, "la fecha de nacimiento no coincide con la CURP");
        if (sex != null && !sex.isBlank() && c.charAt(10) != Character.toUpperCase(sex.trim().charAt(0))) return new Check(false, "el sexo no coincide con la CURP");
        if (!STATES.contains(c.substring(11, 13))) return new Check(false, "entidad de nacimiento desconocida en la CURP");
        String ap = normalize(paternalSurname), nm = normalize(firstNames);
        if (!ap.isEmpty() && c.charAt(0) != firstLetter(ap)) return new Check(false, "la CURP no corresponde al apellido paterno");
        if (!nm.isEmpty() && !givenNameInitials(nm).contains(String.valueOf(c.charAt(3)))) return new Check(false, "la CURP no corresponde al nombre");
        return new Check(true, "CURP válida y consistente con nombre, sexo y fecha de nacimiento");
    }

    public static char curpCheckDigit(String first17) {
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += CURP_ALPHABET.indexOf(first17.charAt(i)) * (18 - i);
        int d = (10 - sum % 10) % 10;
        return (char) ('0' + d);
    }

    // ------------------------------------------------------------------ RFC (persona física)

    public static Check rfc(String raw, LocalDate birthDate, String paternalSurname) {
        String r = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!r.matches("[A-ZÑ&]{4}\\d{6}[A-Z0-9]{2}[0-9A]")) return new Check(false, "estructura de RFC de persona física inválida (13 caracteres)");
        if (rfcCheckDigit(r.substring(0, 12)) != r.charAt(12)) return new Check(false, "dígito verificador de RFC incorrecto");
        if (birthDate != null && !r.substring(4, 10).equals(birthDate.format(YYMMDD))) return new Check(false, "la fecha de nacimiento no coincide con el RFC");
        String ap = normalize(paternalSurname);
        if (!ap.isEmpty() && r.charAt(0) != firstLetter(ap)) return new Check(false, "el RFC no corresponde al apellido paterno");
        return new Check(true, "RFC válido y consistente con la fecha de nacimiento");
    }

    public static char rfcCheckDigit(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int v = RFC_ALPHABET.indexOf(first12.charAt(i));
            sum += Math.max(v, 0) * (13 - i);
        }
        int rem = sum % 11;
        if (rem == 0) return '0';
        if (rem == 1) return 'A';
        return (char) ('0' + (11 - rem));
    }

    // ------------------------------------------------------------------ helpers

    private static char firstLetter(String normalized) {
        String w = normalized.replace("Ñ", "X");
        for (String word : w.split(" ")) {
            if (word.matches("DE|DEL|LA|LAS|LOS|Y|MC|MAC|VON|VAN")) continue;
            return word.charAt(0);
        }
        return w.charAt(0);
    }

    /** First letters of the given names, skipping JOSE and MARIA the way the CURP does. */
    private static String givenNameInitials(String normalized) {
        StringBuilder sb = new StringBuilder();
        String[] words = normalized.replace("Ñ", "X").split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty() || word.matches("DE|DEL|LA|LAS|LOS|Y")) continue;
            if (i == 0 && words.length > 1 && (word.equals("JOSE") || word.equals("MARIA") || word.equals("J") || word.equals("MA"))) continue;
            sb.append(word.charAt(0));
        }
        if (sb.length() == 0 && words.length > 0 && !words[0].isEmpty()) sb.append(words[0].charAt(0));
        return sb.toString();
    }
}
