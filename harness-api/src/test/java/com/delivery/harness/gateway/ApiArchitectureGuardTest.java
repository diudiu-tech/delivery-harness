package com.delivery.harness.gateway;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces via compiled JVM constant pools that HTTP controllers in
 * {@code harness-api} orchestrate exclusively through application/observability
 * services rather than calling stores, fixture implementations, policy engines,
 * residual engines, or extractors directly.
 */
class ApiArchitectureGuardTest {

    private static final Path CONTROLLER_CLASSES = Path.of(
            "target/classes/com/delivery/harness/gateway/controller");

    private static final List<String> FORBIDDEN_INTERNAL_PREFIXES = List.of(
            "com/delivery/harness/evidence/SyntheticEvidenceSource",
            "com/delivery/harness/cases/CaseStore",
            "com/delivery/harness/cases/InMemoryCaseStore",
            "com/delivery/harness/policy/SingleSourcePolicyEngine",
            "com/delivery/harness/policy/PolicyBundleLoader",
            "com/delivery/harness/diagnosis/",
            "com/delivery/harness/extraction/",
            "com/delivery/harness/llm/"
    );

    @Test
    void controllersNeverBypassApplicationServiceInCompiledBytecode() throws IOException {
        assertTrue(Files.isDirectory(CONTROLLER_CLASSES), "Compiled controller classes must exist");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CONTROLLER_CLASSES)) {
            List<Path> classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
            assertTrue(!classFiles.isEmpty(), "Expected compiled controller classes");
            for (Path classFile : classFiles) {
                for (String ref : readReferencedClasses(classFile)) {
                    for (String forbidden : FORBIDDEN_INTERNAL_PREFIXES) {
                        if (ref.startsWith(forbidden)) {
                            violations.add(classFile.getFileName() + " directly references " + ref);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Controller boundary violations:\n" + String.join("\n", violations));
    }

    private static Set<String> readReferencedClasses(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("Invalid class file: " + classFile);
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            List<Integer> classIndices = new ArrayList<>();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7 -> classIndices.add(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("Unknown constant pool tag " + tag);
                }
            }
            Set<String> refs = new HashSet<>();
            for (int idx : classIndices) {
                if (idx > 0 && idx < cpCount && utf8[idx] != null) {
                    refs.add(utf8[idx]);
                }
            }
            return refs;
        }
    }
}
