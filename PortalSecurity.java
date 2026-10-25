import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Small security helpers for controller integration; session storage and authorization remain server responsibilities. */
public final class PortalSecurity {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,128}@[^\\s@]{1,190}\\.[^\\s@]{2,63}$");
    private PortalSecurity() {}

    public static String newCsrfToken() {
        byte[] b = new byte[32]; RNG.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    public static boolean constantTimeTokenMatch(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public static String requireEmail(String email) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) throw new IllegalArgumentException("Enter a valid email address.");
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
    public static String requireText(String value, String label, int max) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required.");
        String v=value.trim(); if(v.length()>max) throw new IllegalArgumentException(label + " is too long."); return v;
    }
    /** Apply these headers to every HTML/API response in the HTTP layer. */
    public static java.util.Map<String,String> recommendedHeaders(boolean production) {
        java.util.Map<String,String> h = new java.util.LinkedHashMap<>();
        h.put("X-Content-Type-Options","nosniff"); h.put("X-Frame-Options","DENY");
        h.put("Referrer-Policy","no-referrer"); h.put("Cache-Control","no-store");
        h.put("Permissions-Policy","camera=(), microphone=(), geolocation=()");
        h.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        if(production) h.put("Strict-Transport-Security","max-age=31536000; includeSubDomains");
        return java.util.Collections.unmodifiableMap(h);
    }
}
