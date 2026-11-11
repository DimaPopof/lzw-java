import java.io.*;
import java.util.*;

public class LZW {
    private static final int CLEAR_CODE = 256;
    private static final int MAX_DICT_SIZE = 4096;

    record Pattern(int prefix, byte current_byte) {}

    /**
     * Compress bytes from {@code in}, writing 16-bit codes directly to {@code out}.
     */
    public static void compress(InputStream in, DataOutputStream out) throws IOException {
        var dict = new HashMap<Pattern, Integer>();
        int[] dictSize = {257}; /* 0-255 are standard characters, 256 is the clear code */

        Runnable resetDict = () -> {
            dict.clear();
            dictSize[0] = 257;
        };

        int firstByte = in.read();
        if (firstByte == -1) {
            return;
        }

        out.writeShort(CLEAR_CODE);
        int outputCodes = 1;
        int prefix = firstByte;

        double bestRatio = 0.0;
        int bytesIn = 1;

        int currentByte;

        while ((currentByte = in.read()) != -1) {
            byte currentByteValue = (byte) currentByte;
            Pattern pattern = new Pattern(prefix, currentByteValue);
            bytesIn++;

            if (dict.containsKey(pattern)) { /* Pattern exists in the dictionary -> move on*/
                prefix = dict.get(pattern);
            } else {                         /* output the prefix and add new pattern to the dictionary */
                out.writeShort(prefix);
                outputCodes++;

                if (dictSize[0] < MAX_DICT_SIZE) {
                    dict.put(pattern, dictSize[0]++);
                }

                prefix = currentByte;
            }

            if (bytesIn % 10000 == 0) {
                double currentRatio = (double) bytesIn / (outputCodes * 2);

                if (currentRatio >= bestRatio) {
                    bestRatio = currentRatio;
                } else {
                    out.writeShort(CLEAR_CODE);
                    outputCodes++;
                    resetDict.run();
                    bestRatio = 0.0;
                }
            }
        }

        out.writeShort(prefix);
    }

    /**
     * Decompress 16-bit codes from {@code in} directly into {@code out}.
     * Only the dictionary and the current LZW entries are kept in memory.
     */
    public static void decompress(DataInputStream in, OutputStream out) throws IOException {
        var dictionary = new ArrayList<byte[]>(MAX_DICT_SIZE);
        int[] nextDictionaryCode = {257};

        Runnable resetDict = () -> {
            dictionary.clear();
            /* Pre-fill the dictionary */
            for (int i = 0; i < 256; ++i) {
                dictionary.add(new byte[]{(byte) i});
            }
            /* Code 256 is reserved for CLEAR, so dictionary code 257 must be stored at index 257. */
            dictionary.add(null);
            nextDictionaryCode[0] = 257;
        };

        resetDict.run();
        int previousCode = -1;
        boolean firstCode = true;

        try {
            while (true) {
                int currentCode = in.readUnsignedShort();

                if (firstCode && currentCode != CLEAR_CODE) {
                    throw new IOException("Input is not a valid LZW stream: expected CLEAR code "
                                          + CLEAR_CODE + " but found " + currentCode);
                }
                firstCode = false;

                /* Handle dictionary resets. */
                if (currentCode == CLEAR_CODE) {
                    resetDict.run();
                    previousCode = -1;
                    continue;
                }

                /* The first code after a reset must be a literal byte. */
                if (previousCode == -1) {
                    if (currentCode > 255) {
                        throw new IOException("Invalid first LZW code: " + currentCode);
                    }
                    out.write(currentCode);
                    previousCode = currentCode;
                    continue;
                }

                byte[] currentEntry;

                /* Reconstruct the pattern for the current code. */
                if (currentCode < dictionary.size()) {
                    currentEntry = dictionary.get(currentCode);
                } else if (currentCode == nextDictionaryCode[0]) {
                    byte[] previousEntry = dictionary.get(previousCode);
                    currentEntry = Arrays.copyOf(previousEntry, previousEntry.length + 1);
                    currentEntry[previousEntry.length] = previousEntry[0];
                } else {
                    throw new IOException("Invalid LZW code sequence: " + currentCode);
                }

                out.write(currentEntry);

                /* Add previous entry + first character of current entry to the dictionary. */
                if (nextDictionaryCode[0] < MAX_DICT_SIZE) {
                    byte[] previousEntry = dictionary.get(previousCode);
                    byte[] previousEntryPlusOne = Arrays.copyOf(previousEntry, previousEntry.length + 1);
                    previousEntryPlusOne[previousEntry.length] = currentEntry[0];
                    dictionary.add(previousEntryPlusOne);

                    nextDictionaryCode[0]++;
                }

                previousCode = currentCode;
            }
        } catch (EOFException endOfInput) {}
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            String testString = "ababcbababa"; 
            runSelfTest(testString.getBytes());
            testString = "TOBEORNOTTOBEORTOBEORNOT"; 
            runSelfTest(testString.getBytes());
            testString = "hello"; 
            runSelfTest(testString.getBytes());
            return;
        }

        if (args.length >= 2) {
            String mode = args[0];
            String inputFilename = args[1];

            if (mode.equals("-c")) {
                String outputFilename = inputFilename + ".my_lzw";
                System.out.println("Compressing '" + inputFilename + "' -> '" + outputFilename + "'...");
                
                try (var bis = new BufferedInputStream(
                               new FileInputStream(inputFilename));
                     var dos = new DataOutputStream(
                               new BufferedOutputStream(
                               new FileOutputStream(outputFilename)))) {
                    compress(bis, dos);
                }
                
                System.out.println("Compression complete. Original: " + new File(inputFilename).length()
                                 + " bytes | Compressed: " + new File(outputFilename).length() + " bytes");
                return;
            } else if (mode.equals("-d")) {
                String outputFilename = inputFilename;
                if (outputFilename.endsWith(".my_lzw")) {
                    outputFilename = outputFilename.substring(0, outputFilename.length() - ".my_lzw".length());
                } else {
                    outputFilename += ".decoded"; /* Fallback if extension is not .my_lzw */
                }
                
                System.out.println("Decompressing '" + inputFilename + "' -> '" + outputFilename + "'...");
                
                try (var dis = new DataInputStream(
                               new BufferedInputStream(
                               new FileInputStream(inputFilename)));
                     var bos = new BufferedOutputStream(
                               new FileOutputStream(outputFilename))) {
                    decompress(dis, bos);
                }
                
                System.out.println("Decompression complete.");
                return;
            }
        }
        
        System.err.println("Usage: java LZW [-c | -d] <filename>");
        System.err.println("  -c : Compress file to <filename>.my_lzw");
        System.err.println("  -d : Decompress <filename>.my_lzw");
    }

    private static void runSelfTest(byte[] data) {
        System.out.println("Running LZW Java Self-Test...");
        System.out.println("Original size: " + data.length + " bytes");

        System.out.println("\n--- COMPRESSION TABLE ---");
        System.out.printf("%-4s | %-15s | %-10s | %-11s | %-20s%n", "Step", "Current Prefix", "Next Char", "Output Code", "New Dict Entry");
        System.out.println("-".repeat(72));
        
        List<Integer> output = new ArrayList<>();
        Map<Pattern, Integer> dict = new HashMap<>();
        int dictSize = 257; 
        
        Map<Integer, String> traceDict = new HashMap<>();
        for (int i = 0; i < 256; i++) traceDict.put(i, "" + (char) i);
        
        int compStep = 1;
        System.out.printf("%-4d | %-15s | %-10s | %-11d | %-20s%n", compStep++, "", "", CLEAR_CODE, "(Clear Code)");
        output.add(CLEAR_CODE);
        int prefix = data[0] & 0xFF;

        for (int i = 1; i < data.length; ++i) {
            byte current_byte = data[i];
            Pattern pattern = new Pattern(prefix, current_byte);

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
        
        List<Integer> compressed = output;
        System.out.println("Origional string: " + new String(data));
        System.out.println("Compressed codes: " + compressed);
        System.out.println("\nCompressed size: " + (compressed.size() * 2) + " bytes");


        System.out.println("\n--- DECOMPRESSION TABLE ---");
        System.out.printf("%-4s | %-11s | %-25s | %-15s | %-20s%n", "Step", "Input Code", "Reconstructed Pattern", "Output", "New Dict Entry");
        System.out.println("-".repeat(87));

        ByteArrayOutputStream outResult = new ByteArrayOutputStream();
        Map<Integer, byte[]> decDict = new HashMap<>();
        int decDictSize = 257;
        for (int i = 0; i < 256; ++i) decDict.put(i, new byte[]{(byte) i});
        
        int oldCode = -1;
        int decStep = 1;

        for (int newCode : compressed) {
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

            try { outResult.write(currentString); } catch (IOException ignored) {}

            String newDictEntryStr = "";
            int newDictCode = decDictSize;
            if (decDictSize < MAX_DICT_SIZE) {
                byte[] newPattern = Arrays.copyOf(oldString, oldString.length + 1);
                newPattern[oldString.length] = currentString[0];
                newDictEntryStr = new String(newPattern);
                decDict.put(decDictSize++, newPattern);
            }
            System.out.printf("%-4d | %-11d | %-25s | %-15s | %d : %s%n", decStep++, newCode, new String(currentString), new String(currentString), newDictCode, newDictEntryStr);
            oldCode = newCode;
        }
        
        byte[] decompressed = outResult.toByteArray();
        System.out.println("Decompressed string: " + new String(decompressed));
        System.out.println("\nDecompressed size: " + decompressed.length + " bytes");

        if (Arrays.equals(data, decompressed)) {
            System.out.println("SUCCESS! Decompressed data perfectly matches original.");
        } else {
            System.out.println("FAILED! Data mismatch.");
        }
    }
}
