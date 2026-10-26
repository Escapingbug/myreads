// Adapted from OpenMOSS/MOSS-TTS-Nano 8b7bcc9, Apache-2.0.
package io.myreads.app.tts;

import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** The upstream robust_pre -> WeText -> robust_post text pipeline. */
public final class OfficialText {
    private static final String CJK = "[\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3040-\\u30ff]";
    private static final String PROT = "___PROT\\d+___";
    private static final String LATIN = "(?:" + PROT + "|(?=[A-Za-z0-9._/+:-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9._/+:-]*)";
    private static final String CLOSERS = "\"')]}）】》〉」』”’";
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static String sub(String text, String regex, String replacement) {
        return Pattern.compile(regex, FLAGS).matcher(text).replaceAll(replacement);
    }
    private static String replace(String text, String regex, Function<MatchResult, String> f) {
        Matcher m = Pattern.compile(regex, FLAGS).matcher(text); StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m.toMatchResult())));
        m.appendTail(out); return out.toString();
    }
    public static String language(String text, String voice) {
        if (Pattern.compile("[\\u3400-\\u9fff]").matcher(text).find()) return "zh";
        if (Pattern.compile("[A-Za-z]").matcher(text).find()) return "en";
        return Arrays.asList("Trump", "Ava", "Bella", "Adam", "Nathan").contains(voice) ? "en" : "zh";
    }
    public static String prepare(String input, String voice, Function<String, String> wetext) {
        String pre = robust(input);
        if (language(pre, voice).equals("zh")) pre = hyphens(pre);
        return robust(wetext.apply(pre));
    }
    public static String robust(String input) {
        String text = input.replace("\r\n", "\n").replace('\r', '\n').replace('\u3000', ' ');
        text = sub(text, "[\\u200b-\\u200d\\ufeff]", "");
        StringBuilder clean = new StringBuilder();
        text.codePoints().forEach(cp -> {
            int t = Character.getType(cp);
            if (cp == '\n' || cp == '\t' || cp == ' ' || t != Character.CONTROL && t != Character.FORMAT
                && t != Character.PRIVATE_USE && t != Character.SURROGATE && t != Character.UNASSIGNED) clean.appendCodePoint(cp);
        });
        text = sub(clean.toString(), "\\[([^\\[\\]]+?)\\]\\((https?://[^)\\s]+)\\)", "$1 $2");
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = strip(raw); if (line.isEmpty()) continue;
            line = sub(line, "^#{1,6}\\s+", ""); line = sub(line, "^>\\s+", "");
            line = sub(line, "^[-*+]\\s+", ""); line = sub(line, "^\\d+[.)]\\s+", "");
            if (!lines.isEmpty()) lines.set(lines.size() - 1, terminal(lines.get(lines.size() - 1)));
            lines.add(line);
        }
        text = arrows(join("", lines)); List<String> protectedText = new ArrayList<>();
        String[] patterns = {
            "https?://[^\\s\\u3000，。！？；、）】》〉」』]+",
            "(?<![\\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![\\w.-])",
            "(?<![A-Za-z0-9_])@[A-Za-z0-9_]{1,32}", "(?<![A-Za-z0-9_])(?:u|r)/[A-Za-z0-9_]+",
            "(?<![A-Za-z0-9_])#(?!\\s)[^\\s#]+",
            "(?<![A-Za-z0-9_])\\.(?=[A-Za-z0-9._-]*[A-Za-z0-9])[A-Za-z0-9._-]+",
            "(?<![A-Za-z0-9_])(?=[A-Za-z0-9._/+:-]*[A-Za-z])(?=[A-Za-z0-9._/+:-]*[./+:-])[A-Za-z0-9][A-Za-z0-9._/+:-]*(?![A-Za-z0-9_])"
        };
        for (String pattern : patterns) text = replace(text, pattern, m -> {
            String placeholder = "___PROT" + protectedText.size() + "___"; protectedText.add(m.group()); return placeholder;
        });
        Matcher protectedMatcher = Pattern.compile(PROT).matcher(text); StringBuilder underscores = new StringBuilder(); int at = 0;
        while (protectedMatcher.find()) {
            underscores.append(text.substring(at, protectedMatcher.start()).replace('_', ' ')).append(protectedMatcher.group());
            at = protectedMatcher.end();
        }
        underscores.append(text.substring(at).replace('_', ' ')); text = spaces(underscores.toString());
        text = sub(text, "\\[\\s*([^\\[\\]]+?)\\s*\\]", "\"$1\"");
        text = sub(text, "\\{\\s*([^{}]+?)\\s*\\}", "\"$1\"");
        text = sub(text, "[【〖『「]\\s*([^】〗』」]+?)\\s*[】〗』」]", "\"$1\"");
        text = sub(text, "(^|[。！？!?；;]\\s*)《([^》]+)》(?=\\s*(?:___PROT\\d+___|[—–―-]{2,}|$|[。！？!?；;，,]))", "$1$2");
        text = sub(arrows(text), "\\s*(?:—|–|―|-){2,}\\s*", "。");
        text = sub(text, "(?:\\.{3,}|…{2,}|……+)", "。");
        text = sub(text, "[。．]{2,}", "。"); text = sub(text, "[，,]{2,}", "，");
        text = sub(text, "[!！]{2,}", "！"); text = sub(text, "[?？]{2,}", "？");
        text = replace(text, "[!?！？]{2,}", m -> {
            boolean q = m.group().matches(".*[?？].*"), e = m.group().matches(".*[!！].*"); return q && e ? "？！" : q ? "？" : "！";
        });
        text = spaces(text);
        for (int i = 0; i < protectedText.size(); i++) text = text.replace("___PROT" + i + "___", protectedText.get(i));
        List<String> result = new ArrayList<>();
        for (String line : strip(text).split("\n", -1)) result.add(strip(line).isEmpty() ? "" : terminal(strip(line)));
        return strip(join("\n", result));
    }
    private static String arrows(String text) {
        return sub(text, "\\s*(?:<[-=]+>|[-=]+>|<[-=]+|[→←↔⇒⇐⇔⟶⟵⟷⟹⟸⟺↦↤↪↩])\\s*", "，");
    }
    private static String spaces(String text) {
        text = sub(text, "[ \\t\\r\\f\\x0b]+", " ");
        text = sub(text, "(" + CJK + ")\\s+(?=" + CJK + ")", "$1");
        text = sub(text, "(" + CJK + ")\\s+(?=\\d)", "$1");
        text = sub(text, "(\\d)\\s+(?=" + CJK + ")", "$1");
        text = sub(text, "(" + CJK + ")(?=(" + LATIN + "))", "$1 ");
        text = sub(text, "((" + LATIN + "))(?=" + CJK + ")", "$1 ");
        text = sub(text, " {2,}", " "); text = sub(text, "\\s+([，。！？；：、”’」』】）》])", "$1");
        text = sub(text, "([（【「『《“‘])\\s+", "$1"); text = sub(text, "([，。！？；：、])\\s*", "$1");
        text = sub(text, "\\s+([,.;!?])", "$1"); return strip(sub(text, " {2,}", " "));
    }
    private static String terminal(String text) {
        int i = text.length();
        while (i > 0 && Character.isWhitespace(text.codePointBefore(i))) i -= Character.charCount(text.codePointBefore(i));
        while (i > 0 && CLOSERS.indexOf(text.charAt(i - 1)) >= 0) i--;
        if (i > 0) {
            int type = Character.getType(text.codePointBefore(i));
            if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.OTHER_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION) return text;
        }
        return text.isEmpty() ? text : text + "。";
    }
    public static String hyphens(String text) {
        String keep = "___KEEP_HYPHEN_BEFORE_ZH_WETEXT___";
        text = sub(text, "(^\\s*)-\\s*(?=\\d)", "$1" + keep);
        text = sub(text, "([=:+*/,(，:：；;（【\\[{])\\s*-\\s*(?=\\d)", "$1" + keep);
        text = sub(text, "([\\u3400-\\u9fff])\\s*-\\s*(?=\\d)", "$1" + keep);
        text = sub(text, "(\\d)\\s*-\\s*(?=\\d)", "$1" + keep);
        text = sub(text, "([\\u3400-\\u9fff])\\s*-\\s*(?=[\\u3400-\\u9fff])", "$1，");
        text = sub(text, "([^\\s-])\\s*-\\s*(?=[^\\s-])", "$1 ");
        return strip(sub(text, " {2,}", " ")).replace(keep, "-");
    }
    static String strip(String text) { return sub(text, "^\\s+|\\s+$", ""); }
    static String join(String separator, List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) { if (result.length() > 0) result.append(separator); result.append(part); }
        return result.toString();
    }
}
