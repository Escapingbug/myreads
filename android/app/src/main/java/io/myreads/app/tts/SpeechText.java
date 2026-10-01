package io.myreads.app.tts;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

final class SpeechText {
    static final String AUDIO_REVISION = "punctuation-v2";
    private static final String DIGITS = "零一二三四五六七八九";
    static String normalize(String input) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        text = replace(text, Pattern.compile("(?<!\\d)(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?!\\d)"),
            m -> digits(m.group(1)) + "年" + number(m.group(2)) + "月" + number(m.group(3)) + "日");
        text = replace(text, Pattern.compile("(?<!\\d)(\\d{4})年"), m -> digits(m.group(1)) + "年");
        text = text.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "");
        text = replace(text, Pattern.compile("(-?\\d+(?:\\.\\d+)?)%"), m -> "百分之" + numeric(m.group(1)));
        text = replace(text, Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?(?!\\d)"),
            m -> number(m.group(1)) + "点" + clockNumber(m.group(2)) + "分" +
                (m.group(3) == null ? "" : clockNumber(m.group(3)) + "秒"));
        text = replace(text, Pattern.compile("-?\\d+(?:\\.\\d+)?"), m -> numeric(m.group()));
        return punctuation(text);
    }
    private static String punctuation(String input) {
        boolean chinese = input.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        // Delimiters describe the printed text, not spoken words. Keep their contents and contractions.
        String text = input.replaceAll("[\"“”„‟«»「」『』《》〈〉【】〖〗()\\[\\]{}]", "")
            .replace('‘', '\'').replace('’', '\'')
            .replaceAll("(?<![A-Za-z])'|'(?![A-Za-z])", "")
            .replaceAll("\\.{2,}|…+", chinese ? "。" : ".")
            .replaceAll("[—–―]+|-{2,}", ",");
        if (chinese) {
            text = text.replace(',', '，').replace('、', '，').replace(':', '，').replace('：', '，')
                .replace(';', '。').replace('；', '。').replace('?', '？').replace('!', '！')
                .replaceAll("(?<=\\p{IsHan})\\.(?![A-Za-z])", "。");
        }
        text = text.replaceAll("[，,]{2,}", chinese ? "，" : ",");
        text = replace(text, Pattern.compile("[。！？!?]{2,}"), m -> {
            String marks = m.group();
            boolean question = marks.indexOf('？') >= 0 || marks.indexOf('?') >= 0;
            boolean exclamation = marks.indexOf('！') >= 0 || marks.indexOf('!') >= 0;
            if (question && exclamation) return chinese ? "？！" : "?!";
            if (question) return chinese ? "？" : "?";
            if (exclamation) return chinese ? "！" : "!";
            return "。";
        });
        return text.replaceAll("[，,]\\s*(?=[。！？!?])|(?<=[。！？!?])\\s*[，,]", "")
            .replaceAll("^[\\s，,]+|[\\s，,]+$", "").trim();
    }
    private interface Replacement { String apply(Matcher match); }
    private static String replace(String input, Pattern pattern, Replacement replacement) {
        Matcher match = pattern.matcher(input); StringBuffer result = new StringBuffer();
        while (match.find()) match.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(match)));
        match.appendTail(result); return result.toString();
    }
    private static String numeric(String input) {
        if (input.startsWith("-")) return "负" + numeric(input.substring(1));
        String[] pieces = input.split("\\.", -1);
        String integer = pieces[0];
        String result = (integer.length() >= 7 || (integer.length() > 1 && integer.startsWith("0"))) ? digits(integer) : number(integer);
        return pieces.length > 1 ? result + "点" + digits(pieces[1]) : result;
    }
    private static String digits(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) result.append(DIGITS.charAt(c - '0'));
        return result.toString();
    }
    private static String clockNumber(String value) {
        int n = Integer.parseInt(value);
        return (n > 0 && n < 10 ? "零" : "") + number(value);
    }
    private static String number(String value) {
        long n = Long.parseLong(value);
        if (n == 0) return "零";
        if (n >= 10000) return number(String.valueOf(n / 10000)) + "万" +
            (n % 10000 == 0 ? "" : (n % 10000 < 1000 ? "零" : "") + number(String.valueOf(n % 10000)));
        String[] units = {"", "十", "百", "千"}; StringBuilder result = new StringBuilder();
        boolean zero = false;
        for (int i = 3; i >= 0; i--) {
            int base = (int) Math.pow(10, i); int digit = (int) (n / base); n %= base;
            if (digit == 0) { if (result.length() > 0 && n > 0) zero = true; continue; }
            if (zero) { result.append("零"); zero = false; }
            if (!(digit == 1 && i == 1 && result.length() == 0)) result.append(DIGITS.charAt(digit));
            result.append(units[i]);
        }
        return result.toString();
    }
    static List<String> segments(String input) {
        String text = normalize(input);
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int limit = text.offsetByCodePoints(start, Math.min(48, text.codePointCount(start, text.length())));
            int end = limit, sentenceEnd = -1;
            if (limit < text.length()) for (int i = start; i < limit; i++) {
                if ("。！？!?；;\n".indexOf(text.charAt(i)) >= 0) sentenceEnd = i + 1;
            }
            if (sentenceEnd > start) end = sentenceEnd;
            else if (limit < text.length()) {
                for (int i = limit - 1; i > start + 12; i--) {
                    if ("，、,:： ".indexOf(text.charAt(i)) >= 0) { end = i + 1; break; }
                }
            }
            // Keep combined question/exclamation marks together even at the text budget edge.
            while (end < text.length() && "。！？!?".indexOf(text.charAt(end - 1)) >= 0
                && "。！？!?".indexOf(text.charAt(end)) >= 0) end++;
            String part = text.substring(start, end).trim();
            if (part.codePoints().anyMatch(Character::isLetterOrDigit)) result.add(part);
            start = end;
        }
        return result;
    }
}
