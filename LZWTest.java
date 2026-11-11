import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Comprehensive test suite for LZW compression and decompression.
 * 
 * Usage:
 *   java LZWTest          - Runs all automated tests and asserts correctness
 *   java LZWTest --table  - Also prints the step-by-step compression/decompression trace table
 */
public class LZWTest {
    private static final int CLEAR_CODE = 256;
    private static final int MAX_DICT_SIZE = 4096;

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        boolean showTable = args.length > 0 && (args[0].equals("--table") || args[0].equals("-t"));

        System.out.println("==================================================");
        System.out.println("          RUNNING LZW AUTOMATED TEST SUITE        ");
        System.out.println("==================================================");

        runTest("Empty input", LZWTest::testEmptyInput);
        runTest("Single byte", LZWTest::testSingleByte);
        runTest("Basic strings (ababcbababa, TOBEORNOT...)", LZWTest::testBasicStrings);
        runTest("All 256 byte values (binary integrity)", LZWTest::testAll256ByteValues);
        runTest("UTF-8 Cyrillic & Unicode text", LZWTest::testUnicodeText);
        runTest("Repetitive data (ratio & integrity)", LZWTest::testRepetitiveData);
        runTest("Large random data (50 KB)", LZWTest::testLargeRandomData);
        runTest("Dictionary overflow and reset behavior", LZWTest::testDictionaryReset);
        runTest("Corrupted stream rejection", LZWTest::testCorruptedStream);

        System.out.println("\n--------------------------------------------------");
        System.out.printf("Total tests: %d | Passed: %d | Failed: %d%n",
                (testsPassed + testsFailed), testsPassed, testsFailed);
        System.out.println("--------------------------------------------------");

        if (showTable) {
            System.out.println("\n>>> Running visual trace table for 'ababcbababa' <<<");
            printTraceTable("ababcbababa".getBytes(StandardCharsets.UTF_8));
        } else {
            System.out.println("\nTip: Run 'java LZWTest --table' to see the step-by-step algorithm trace table.");
        }

        if (testsFailed > 0) {
            System.err.println("\n[FAILED] Some tests did not pass.");
            System.exit(1);
        } else {
            System.out.println("\n[OK] All tests completed successfully!");
        }
    }

    private static void runTest(String testName, TestCase testCase) {
        System.out.printf("[TEST] %-42s ... ", testName);
        long startTime = System.nanoTime();
        try {
            testCase.execute();
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf("PASSED (%d ms)%n", elapsedMs);
            testsPassed++;
        } catch (Throwable t) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            System.out.printf("FAILED (%d ms)%n", elapsedMs);
            System.err.println("       Error: " + t.getMessage());
            t.printStackTrace(System.err);
            testsFailed++;
        }
    }

    @FunctionalInterface
    interface TestCase {
        void execute() throws Exception;
    }

    /* Helper: Compress in-memory */
    private static byte[] compress(byte[] input) throws IOException {
        var in = new ByteArrayInputStream(input);
        var out = new ByteArrayOutputStream();
        try (var dos = new DataOutputStream(out)) {
            LZW.compress(in, dos);
        }
        return out.toByteArray();
    }

    /* Helper: Decompress in-memory */
    private static byte[] decompress(byte[] compressed) throws IOException {
        var in = new ByteArrayInputStream(compressed);
        var dis = new DataInputStream(in);
        var out = new ByteArrayOutputStream();
        LZW.decompress(dis, out);
        return out.toByteArray();
    }

    /* Helper: Assert round-trip equality */
    private static void assertRoundTrip(byte[] original, String description) throws IOException {
        byte[] compressed = compress(original);
        byte[] decompressed = decompress(compressed);

        if (!Arrays.equals(original, decompressed)) {
            throw new AssertionError("Mismatch in " + description + ": expected " + original.length
                    + " bytes, but got " + decompressed.length + " bytes.");
        }
    }

    // =========================================================================
    // Automated Test Cases
    // =========================================================================

    private static void testEmptyInput() throws IOException {
        byte[] empty = new byte[0];
        byte[] compressed = compress(empty);
        byte[] decompressed = decompress(compressed);
        if (compressed.length != 0 || decompressed.length != 0) {
            throw new AssertionError("Empty input should produce empty output");
        }
    }

    private static void testSingleByte() throws IOException {
        byte[] single = new byte[]{(byte) 'Z'};
        assertRoundTrip(single, "Single byte");
    }

    private static void testBasicStrings() throws IOException {
        String[] samples = {
            "ababcbababa",
            "TOBEORNOTTOBEORTOBEORNOT",
            "hello",
            "AAAAAAAAAAAAAAAAAAAA",
            "The quick brown fox jumps over the lazy dog"
        };
        for (String sample : samples) {
            assertRoundTrip(sample.getBytes(StandardCharsets.UTF_8), "String: '" + sample + "'");
        }
    }

    private static void testAll256ByteValues() throws IOException {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        assertRoundTrip(allBytes, "All 256 byte values sequence");
    }

    private static void testUnicodeText() throws IOException {
        String cyrillicText = """
                Алгоритм Лемпеля — Зива — Велча (LZW) — алгоритм стиснення даних без втрат.
                Створений Абрахамом Лемпелем, Яковом Зівом і Террі Велчем.
                Привіт, світе! 1234567890 !@#$%^&*()_+~
                """;
        assertRoundTrip(cyrillicText.getBytes(StandardCharsets.UTF_8), "Unicode / Cyrillic Text");
    }

    private static void testRepetitiveData() throws IOException {
        String pattern = "ABCD_1234_LZW_COMPRESSION_";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append(pattern);
        }
        byte[] original = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(original);
        byte[] decompressed = decompress(compressed);

        if (!Arrays.equals(original, decompressed)) {
            throw new AssertionError("Data mismatch for repetitive text.");
        }

        // Repetitive text must achieve high compression ratio
        double ratio = (double) original.length / compressed.length;
        if (ratio < 2.0) {
            throw new AssertionError("Expected compression ratio > 2.0, but got: " + ratio);
        }
    }

    private static void testLargeRandomData() throws IOException {
        Random rnd = new Random(42);
        byte[] randomBytes = new byte[50_000];
        rnd.nextBytes(randomBytes);
        assertRoundTrip(randomBytes, "50 KB Pseudo-Random Data");
    }

    private static void testDictionaryReset() throws IOException {
        // Generate enough data with varying vocabulary to exceed dictionary size
        // and trigger dictionary resets
        Random rnd = new Random(123);
        byte[] largeData = new byte[150_000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (rnd.nextInt(50) + (i / 10000));
        }
        assertRoundTrip(largeData, "150 KB Data triggering dictionary fill and resets");
    }

    private static void testCorruptedStream() {
        // Stream starting with an invalid code instead of CLEAR_CODE (256)
        byte[] corrupted = new byte[]{0x00, 0x10, 0x00, 0x20};
        try {
            decompress(corrupted);
            throw new AssertionError("Expected IOException when decompressing corrupted stream");
        } catch (IOException expected) {
            // Success: properly rejected
        }
    }

    // =========================================================================
    // Detailed Visual Trace Table (for demonstrations and university reports)
    // =========================================================================

    private static void printTraceTable(byte[] data) {
        System.out.println("Original size: " + data.length + " bytes");

        System.out.println("\n--- COMPRESSION TABLE ---");
        System.out.printf("%-4s | %-15s | %-10s | %-11s | %-20s%n", "Step", "Current Prefix", "Next Char", "Output Code", "New Dict Entry");
        System.out.println("-".repeat(72));

        List<Integer> output = new ArrayList<>();
        record TracePattern(int prefix, byte currentByte) {}
        Map<TracePattern, Integer> dict = new HashMap<>();
        int dictSize = 257;

        Map<Integer, String> traceDict = new HashMap<>();
        for (int i = 0; i < 256; i++) traceDict.put(i, "" + (char) i);

        int compStep = 1;
        System.out.printf("%-4d | %-15s | %-10s | %-11d | %-20s%n", compStep++, "", "", CLEAR_CODE, "(Clear Code)");
        output.add(CLEAR_CODE);
        int prefix = data[0] & 0xFF;

        for (int i = 1; i < data.length; ++i) {
            byte current_byte = data[i];
            TracePattern pattern = new TracePattern(prefix, current_byte);

            if (dict.containsKey(pattern)) {
                System.out.printf("%-4d | %-15s | %-10s | %-11s | %-20s%n", compStep++, traceDict.get(prefix), (char) current_byte, "", "");
                prefix = dict.get(pattern);
            } else {
                output.add(prefix);
                String newEntry = traceDict.get(prefix) + (char) current_byte;
                if (dictSize < MAX_DICT_SIZE) {
                    traceDict.put(dictSize, newEntry);
                    dict.put(pattern, dictSize++);
                }
                System.out.printf("%-4d | %-15s | %-10s | %-11d | %s%n", compStep++, traceDict.get(prefix), (char) current_byte, prefix,
                        dictSize <= MAX_DICT_SIZE ? (dictSize - 1) + " : " + newEntry : "");
                prefix = current_byte & 0xFF;
            }
        }
        output.add(prefix);
        System.out.printf("%-4d | %-15s | %-10s | %-11d | %-20s%n", compStep++, traceDict.get(prefix), "EOF", prefix, "");

        System.out.println("Original string: " + new String(data, StandardCharsets.UTF_8));
        System.out.println("Compressed codes: " + output);
        System.out.println("Compressed size: " + (output.size() * 2) + " bytes");

        System.out.println("\n--- DECOMPRESSION TABLE ---");
        System.out.printf("%-4s | %-11s | %-25s | %-15s | %-20s%n", "Step", "Input Code", "Reconstructed Pattern", "Output", "New Dict Entry");
        System.out.println("-".repeat(87));

        ByteArrayOutputStream outResult = new ByteArrayOutputStream();
        Map<Integer, byte[]> decDict = new HashMap<>();
        int decDictSize = 257;
        for (int i = 0; i < 256; ++i) decDict.put(i, new byte[]{(byte) i});

        int oldCode = -1;
        int decStep = 1;

        for (int newCode : output) {
            if (newCode == CLEAR_CODE) {
                System.out.printf("%-4d | %-11d | %-25s | %-15s | %-20s%n", decStep++, newCode, "(Clear Code)", "", "(Reset Dict)");
                oldCode = -1;
                continue;
            }

            if (oldCode == -1) {
                System.out.printf("%-4d | %-11d | %-25s | %-15s | %-20s%n", decStep++, newCode, new String(new byte[]{(byte) newCode}), new String(new byte[]{(byte) newCode}), "");
                outResult.write((byte) newCode);
                oldCode = newCode;
                continue;
            }

            byte[] oldString = decDict.get(oldCode);
            byte[] currentString;
            if (decDict.containsKey(newCode)) {
                currentString = decDict.get(newCode);
            } else if (newCode >= decDictSize) {
                currentString = Arrays.copyOf(oldString, oldString.length + 1);
                currentString[oldString.length] = oldString[0];
            } else {
                throw new RuntimeException("Invalid LZW code sequence.");
            }

            outResult.write(currentString, 0, currentString.length);

            String newDictEntryStr = "";
            int newDictCode = decDictSize;
            if (decDictSize < MAX_DICT_SIZE) {
                byte[] newPattern = Arrays.copyOf(oldString, oldString.length + 1);
                newPattern[oldString.length] = currentString[0];
                newDictEntryStr = new String(newPattern, StandardCharsets.UTF_8);
                decDict.put(decDictSize++, newPattern);
            }
            System.out.printf("%-4d | %-11d | %-25s | %-15s | %d : %s%n", decStep++, newCode, new String(currentString), new String(currentString), newDictCode, newDictEntryStr);
            oldCode = newCode;
        }

        byte[] decompressed = outResult.toByteArray();
        System.out.println("\nDecompressed string: " + new String(decompressed, StandardCharsets.UTF_8));
        System.out.println("Decompressed size: " + decompressed.length + " bytes");
    }
}
