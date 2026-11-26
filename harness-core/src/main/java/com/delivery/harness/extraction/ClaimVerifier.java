package com.delivery.harness.extraction;

import com.delivery.harness.evidence.ConversationMessage;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.SpeakerRole;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic verifier enforcing the trust boundary around unstructured
 * extraction (ADR-0011):
 * <ul>
 *   <li>defense-in-depth prompt-injection pre-scan marking {@code UNTRUSTED_TEXT}
 *       (stripping invisible format/control chars and collapsing whitespace/separators)</li>
 *   <li>NFKC + Unicode {@code strip()} normalization and single-message exact
 *       substring offset match across all occurrences</li>
 *   <li>minimum quote length to prevent single-word cherry-picking</li>
 *   <li>metadata-sourced {@link SpeakerRole} enforcement (rejects self-serving
 *       rider statements for {@code CUSTOMER_CHANGED_ADDRESS})</li>
 *   <li>preceding and inline negation-window detection with multi-occurrence
 *       awareness</li>
 *   <li>zero digits/currency symbols in free-text {@code statement} fields</li>
 * </ul>
 */
@Component
public class ClaimVerifier {

    public static final int MIN_QUOTE_LENGTH = 4;
    private static final int NEGATION_WINDOW_CHARS = 12;

    private static final List<String> CJK_INJECTION_PATTERNS = List.of(
            "忽略以上", "忽略之前", "忽略所有指令", "系统提示", "直接输出",
            "免除骑手责任", "跳过人工"
    );

    private static final List<String> EN_INJECTION_PATTERNS = List.of(
            "ignore previous", "ignore above", "ignore all instructions",
            "system prompt", "developer prompt"
    );

    private static final List<String> NEGATION_CUES = List.of(
            "我没有", "并未", "并没有", "从未", "根本没", "不是",
            "没有改", "没改", "未改", "没要求", "没有要求", "没同意",
            "不换", "没有洒", "没洒", "没有撒", "没撒", "未洒", "完好"
    );

    private static final Pattern INVISIBLE_FORMAT_OR_CONTROL = Pattern.compile("[\\p{Cf}\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern CJK_INLINE_SEPARATORS = Pattern.compile("[\\s·•._\\-|/\\\\]+");
    private static final Pattern FREE_TEXT_DIGIT_OR_CURRENCY = Pattern.compile("[0-9０-９¥￥$€£元块角分]");

    public record CandidateClaim(
            String signalType,
            String sourceEvidenceId,
            String quote,
            String statement) {}

    public record VerificationResult(
            List<ClaimedFact> accepted,
            List<String> rejections) {

        public boolean hasRejections() {
            return !rejections.isEmpty();
        }
    }

    /**
     * Defense-in-depth pre-scan for prompt-injection markers across snapshot
     * conversation text. Strips Unicode zero-width/format characters and
     * collapses whitespace/inline separators before matching. Matches mark the
     * text untrusted so extraction is skipped and the case routes to escalation.
     */
    public boolean detectUntrustedText(EvidenceSnapshot snapshot) {
        if (snapshot == null || snapshot.messages().isEmpty()) {
            return false;
        }
        for (ConversationMessage message : snapshot.messages()) {
            String cleaned = INVISIBLE_FORMAT_OR_CONTROL
                    .matcher(normalize(message.text()))
                    .replaceAll("")
                    .toLowerCase(Locale.ROOT);
            String collapsedSpaces = WHITESPACE_RUN.matcher(cleaned).replaceAll(" ");
            for (String marker : EN_INJECTION_PATTERNS) {
                if (collapsedSpaces.contains(marker)) {
                    return true;
                }
            }
            String compactCjk = CJK_INLINE_SEPARATORS.matcher(cleaned).replaceAll("");
            for (String marker : CJK_INJECTION_PATTERNS) {
                if (compactCjk.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    public VerificationResult verify(
            EvidenceSnapshot snapshot,
            List<CandidateClaim> candidates,
            String extractor,
            String promptHash) {
        List<ClaimedFact> accepted = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return new VerificationResult(List.of(), List.of());
        }

        for (CandidateClaim candidate : candidates) {
            Optional<SignalType> parsedType = SignalType.parse(candidate.signalType());
            if (parsedType.isEmpty()) {
                rejections.add("UNKNOWN_SIGNAL_TYPE:" + candidate.signalType());
                continue;
            }
            SignalType signalType = parsedType.get();

            Optional<ConversationMessage> messageOpt = snapshot.findMessageById(candidate.sourceEvidenceId());
            if (messageOpt.isEmpty()) {
                rejections.add("UNKNOWN_EVIDENCE_ID:" + candidate.sourceEvidenceId());
                continue;
            }
            ConversationMessage message = messageOpt.get();

            String normalizedMsg = normalize(message.text());
            String normalizedQuote = normalize(candidate.quote());
            if (normalizedQuote.length() < MIN_QUOTE_LENGTH) {
                rejections.add("QUOTE_TOO_SHORT:" + candidate.sourceEvidenceId());
                continue;
            }

            List<int[]> occurrences = findAllOccurrences(normalizedMsg, normalizedQuote);
            if (occurrences.isEmpty()) {
                rejections.add("QUOTE_NOT_FOUND_IN_SINGLE_MESSAGE:" + candidate.sourceEvidenceId());
                continue;
            }

            if (!isSpeakerAuthorizedForSignal(signalType, message.speakerRole())) {
                rejections.add("SELF_SERVING_OR_UNAUTHORIZED_SPEAKER:"
                        + signalType.name() + ":" + message.speakerRole().name());
                continue;
            }

            int[] validSpan = selectValidNonNegatedSpan(normalizedMsg, occurrences);
            if (validSpan == null) {
                rejections.add("NEGATED_CLAIM:" + signalType.name() + ":" + candidate.sourceEvidenceId());
                continue;
            }

            String statement = candidate.statement() == null ? null : normalize(candidate.statement());
            if (statement != null && FREE_TEXT_DIGIT_OR_CURRENCY.matcher(statement).find()) {
                rejections.add("NUMERIC_FREE_TEXT_FORBIDDEN:" + candidate.sourceEvidenceId());
                continue;
            }

            accepted.add(new ClaimedFact(
                    signalType,
                    message.speakerRole(),
                    message.evidenceId(),
                    validSpan[0],
                    validSpan[1],
                    normalizedQuote,
                    extractor,
                    promptHash,
                    statement));
        }

        return new VerificationResult(List.copyOf(accepted), List.copyOf(rejections));
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
    }

    private static List<int[]> findAllOccurrences(String text, String sub) {
        List<int[]> spans = new ArrayList<>();
        int from = 0;
        while (from <= text.length() - sub.length()) {
            int idx = text.indexOf(sub, from);
            if (idx < 0) {
                break;
            }
            spans.add(new int[] {idx, idx + sub.length()});
            from = idx + 1;
        }
        return spans;
    }

    /**
     * Selects a non-negated occurrence of the quote, provided no later
     * occurrence in the same message explicitly negates/retracts it.
     */
    private static int[] selectValidNonNegatedSpan(String normalizedMsg, List<int[]> occurrences) {
        int[] latest = occurrences.get(occurrences.size() - 1);
        if (isNegated(normalizedMsg, latest[0], latest[1])) {
            return null;
        }
        for (int[] span : occurrences) {
            if (!isNegated(normalizedMsg, span[0], span[1])) {
                return span;
            }
        }
        return null;
    }

    private static boolean isSpeakerAuthorizedForSignal(SignalType type, SpeakerRole role) {
        return switch (type) {
            case CUSTOMER_CHANGED_ADDRESS -> role == SpeakerRole.CUSTOMER;
            case MERCHANT_ITEM_SWAP -> role == SpeakerRole.MERCHANT;
            case DAMAGE_CLAIM -> role == SpeakerRole.CUSTOMER || role == SpeakerRole.RIDER;
            case RIDER_ACKNOWLEDGED_DELAY -> role == SpeakerRole.RIDER;
        };
    }

    private static boolean isNegated(String normalizedMessage, int start, int end) {
        int windowStart = Math.max(0, start - NEGATION_WINDOW_CHARS);
        String window = normalizedMessage.substring(windowStart, end);
        for (String cue : NEGATION_CUES) {
            if (window.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
