package com.delivery.harness.runtime;

import com.delivery.harness.common.exception.RuntimeBoundaryException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects explicit personal-data formats, not arbitrary names or addresses in natural language. */
public final class PersonalDataGuard {
    private static final Pattern PHONE =
            Pattern.compile(
                    "(?<![0-9])(?:\\+?86[\\s\\p{Z}-]*)?1[3-9](?:[\\s\\p{Z}-]*[0-9]){9}(?![0-9])");
    private static final Pattern EMAIL =
            Pattern.compile(
                    "[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9.-]*[A-Z0-9])?\\.[A-Z]{2,}",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY =
            Pattern.compile(
                    "(?<![0-9])(?:[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])"
                            + "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9X]|[1-9][0-9]{5}[0-9]{2}"
                            + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3})(?![0-9])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL =
            Pattern.compile(
                    "(?:姓名|客户姓名|用户姓名|真实姓名|收货人|联系人|地址|收货地址|配送地址|详细地址|家庭地址|住址|身份证号?|证件号码?|full[_"
                        + " ]?name|customer[_ ]?name|recipient[_ ]?name|contact[_"
                        + " ]?name|(?:delivery|shipping|home|recipient)[_"
                        + " ]?address|address)[\\s\"'`]*[:=][\\s\\p{Z}]*([^\\r"
                        + "\\n"
                        + ",，;；}]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern REDACTED =
            Pattern.compile(
                    "(?:\\*{3,}|\\[(?:已脱敏|姓名已脱敏|地址已脱敏|REDACTED|MASKED)]|<REDACTED>)",
                    Pattern.CASE_INSENSITIVE);

    private PersonalDataGuard() {}

    public static List<String> findings(String... texts) {
        StringBuilder combined = new StringBuilder();
        for (String text : texts) {
            if (text != null) {
                combined.append('\n').append(text);
            }
        }
        String normalized =
                Normalizer.normalize(combined, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
        List<String> findings = new ArrayList<>();
        if (PHONE.matcher(normalized).find()) {
            findings.add("POTENTIAL_PHONE_NUMBER");
        }
        if (EMAIL.matcher(normalized).find()) {
            findings.add("POTENTIAL_EMAIL_ADDRESS");
        }
        if (IDENTITY.matcher(normalized).find()) {
            findings.add("POTENTIAL_IDENTITY_NUMBER");
        }
        Matcher labels = LABEL.matcher(normalized);
        while (labels.find()) {
            String value = labels.group(1).trim().replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
            if (!value.isEmpty() && !REDACTED.matcher(value).matches()) {
                findings.add("POTENTIAL_NAME_OR_ADDRESS_LABEL");
                break;
            }
        }
        return findings;
    }

    public static void requireKnowledgeSafe(Map<String, Object> knowledge) {
        List<String> texts = new ArrayList<>();
        texts.add(RuntimeData.text(knowledge, "title", ""));
        texts.add(RuntimeData.text(knowledge, "content", ""));
        for (Map<String, Object> chunk : RuntimeData.maps(knowledge.get("chunks"))) {
            texts.add(RuntimeData.text(chunk, "title_path", ""));
            texts.add(RuntimeData.text(chunk, "content", ""));
        }
        requireClean("KNOWLEDGE_CONTAINS_PERSONAL_DATA", texts.toArray(String[]::new));
    }

    public static void requireClean(String errorCode, String... texts) {
        List<String> findings = findings(texts);
        if (!findings.isEmpty()) {
            // The error must never echo the personal-data value it rejected.
            throw new RuntimeBoundaryException(
                    422,
                    errorCode,
                    "Remove explicit personal data before use: " + String.join(",", findings));
        }
    }
}
