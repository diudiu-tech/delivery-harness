package com.delivery.harness.extraction;

import com.delivery.harness.evidence.ConversationMessage;
import com.delivery.harness.evidence.EvidenceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Honest ~20-line keyword/regex baseline that any LLM extractor must beat on
 * held-out paraphrased conversations to earn its latency and token cost
 * (ADR-0005 / Rev 2.1 P0-8).
 */
@Component
@RequiredArgsConstructor
public class RegexSignalBaseline {

    private static final Map<SignalType, Pattern> PATTERNS = Map.of(
            SignalType.CUSTOMER_CHANGED_ADDRESS, Pattern.compile("(改地址|改送到[^，。！？]{1,12}|麻烦送到[^，。！？]{1,12}|送到对面[^，。！？]{1,12})"),
            SignalType.MERCHANT_ITEM_SWAP, Pattern.compile("(换成[^，。！？]{1,10}|替换为[^，。！？]{1,10}|卖完了[^，。！？]{0,10}换)"),
            SignalType.DAMAGE_CLAIM, Pattern.compile("(撒了[^，。！？]{0,8}|洒了[^，。！？]{0,8}|洒出来|压扁[^，。！？]{0,8}|漏在[^，。！？]{0,8})"),
            SignalType.RIDER_ACKNOWLEDGED_DELAY, Pattern.compile("(绕路走错|走错了[^，。！？]{0,8}|看错导航[^，。！？]{0,8})")
    );

    private final ClaimVerifier verifier;

    public BaselineResult extract(EvidenceSnapshot snapshot) {
        if (verifier.detectUntrustedText(snapshot)) {
            return new BaselineResult(List.of(), true, List.of());
        }
        List<ClaimVerifier.CandidateClaim> candidates = new ArrayList<>();
        for (ConversationMessage msg : snapshot.messages()) {
            String text = ClaimVerifier.normalize(msg.text());
            for (Map.Entry<SignalType, Pattern> entry : PATTERNS.entrySet()) {
                Matcher matcher = entry.getValue().matcher(text);
                if (matcher.find()) {
                    candidates.add(new ClaimVerifier.CandidateClaim(
                            entry.getKey().name(), msg.evidenceId(), matcher.group(1), null));
                }
            }
        }
        ClaimVerifier.VerificationResult verified = verifier.verify(snapshot, candidates, "REGEX_BASELINE", null);
        return new BaselineResult(verified.accepted(), false, verified.rejections());
    }

    public record BaselineResult(
            List<ClaimedFact> signals,
            boolean untrustedTextDetected,
            List<String> rejections) {}
}
