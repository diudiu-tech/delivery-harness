package com.delivery.harness.architecture;

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
 * Zero-dependency bytecode architecture guard inspecting compiled JVM constant
 * pools in {@code target/classes}.
 *
 * <p>Enforces the structural boundaries committed in Rev 2.1 without adding
 * heavyweight test-time dependencies:
 * <ol>
 *   <li>Production code must never reference test truth labels ({@code labels.json}).</li>
 *   <li>{@code policy} package must never depend on {@code extraction} or {@code llm}
 *       (structural guarantee that adjudication is invariant to LLM outputs).</li>
 *   <li>Pure decision packages ({@code policy}, {@code evidence}, {@code agent.analysis})
 *       must never depend on Spring Web, HTTP clients, or {@code llm}.</li>
 * </ol>
 */
class ArchitectureGuardTest {

    private static final Path CORE_CLASSES = Path.of("target/classes");

    @Test
    void productionCodeNeverReferencesGroundTruthLabels() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ClassInspection clazz : inspectAllClasses(CORE_CLASSES)) {
            for (String utf8 : clazz.utf8Constants()) {
                if (utf8.contains("labels.json") || utf8.contains("com/delivery/harness/eval/labels")) {
                    violations.add(clazz.className() + " references forbidden label artifact: " + utf8);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Label isolation violations:\n" + String.join("\n", violations));
    }

    @Test
    void policyPackageNeverDependsOnExtractionOrLlm() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ClassInspection clazz : inspectAllClasses(CORE_CLASSES)) {
            if (!clazz.className().startsWith("com/delivery/harness/policy/")) {
                continue;
            }
            for (String ref : clazz.referencedClasses()) {
                if (ref.startsWith("com/delivery/harness/extraction/")
                        || ref.startsWith("com/delivery/harness/llm/")) {
                    violations.add(clazz.className() + " depends on forbidden package: " + ref);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Policy isolation violations:\n" + String.join("\n", violations));
    }

    @Test
    void pureDecisionPackagesNeverDependOnWebHttpOrLlm() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ClassInspection clazz : inspectAllClasses(CORE_CLASSES)) {
            boolean inPureKernel = clazz.className().startsWith("com/delivery/harness/policy/")
                    || clazz.className().startsWith("com/delivery/harness/evidence/")
                    || clazz.className().startsWith("com/delivery/harness/diagnosis/")
                    || clazz.className().startsWith("com/delivery/harness/template/")
                    || clazz.className().startsWith("com/delivery/harness/agent/analysis/");
            if (!inPureKernel) {
                continue;
            }
            for (String ref : clazz.referencedClasses()) {
                if (ref.startsWith("org/springframework/web/")
                        || ref.startsWith("java/net/http/")
                        || ref.equals("java/net/HttpURLConnection")
                        || ref.startsWith("com/delivery/harness/llm/")) {
                    violations.add(clazz.className() + " depends on forbidden IO/Web/LLM type: " + ref);
                }
            }
        }
        assertTrue(violations.isEmpty(), "Pure kernel boundary violations:\n" + String.join("\n", violations));
    }

    static List<ClassInspection> inspectAllClasses(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "Compiled classes directory must exist: " + root.toAbsolutePath());
        List<ClassInspection> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
            assertTrue(!classFiles.isEmpty(), "Expected compiled classes under " + root);
            for (Path classFile : classFiles) {
                result.add(readClassFile(classFile));
            }
        }
        return result;
    }

    /**
     * Parses JVM classfile constant pool (JVMS §4.4) to extract all referenced
     * class internal names and UTF-8 string constants.
     */
    private static ClassInspection readClassFile(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Not a valid class file: " + classFile);
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();

            String[] utf8ByIndex = new String[cpCount];
            List<Integer> classNameIndices = new ArrayList<>();
            Set<String> allUtf8 = new HashSet<>();

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> { // CONSTANT_Utf8
                        String s = in.readUTF();
                        utf8ByIndex[i] = s;
                        allUtf8.add(s);
                    }
                    case 3, 4 -> in.readInt(); // Integer, Float
                    case 5, 6 -> { // Long, Double take two constant-pool slots
                        in.readLong();
                        i++;
                    }
                    case 7 -> classNameIndices.add(in.readUnsignedShort()); // CONSTANT_Class
                    case 8, 16, 19, 20 -> in.readUnsignedShort(); // String, MethodType, Module, Package
                    case 9, 10, 11, 12, 17, 18 -> { // Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> { // MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("Unknown constant pool tag " + tag + " in " + classFile);
                }
            }

            in.readUnsignedShort(); // access_flags
            int thisClassCpIndex = in.readUnsignedShort();
            // Resolve thisClass name via relative path for simplicity and accuracy
            String rel = CORE_CLASSES.relativize(classFile).toString().replace('\\', '/');
            String className = rel.substring(0, rel.length() - ".class".length());

            Set<String> referencedClasses = new HashSet<>();
            for (int nameIdx : classNameIndices) {
                if (nameIdx > 0 && nameIdx < cpCount && utf8ByIndex[nameIdx] != null) {
                    referencedClasses.add(utf8ByIndex[nameIdx]);
                }
            }
            if (thisClassCpIndex < 0) {
                throw new IOException("Invalid this_class index");
            }
            return new ClassInspection(className, referencedClasses, allUtf8);
        }
    }

    record ClassInspection(String className, Set<String> referencedClasses, Set<String> utf8Constants) {}
}
