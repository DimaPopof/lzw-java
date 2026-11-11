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
                    out.writeShort(prefix);
                    outputCodes++;
                    out.writeShort(CLEAR_CODE);
                    outputCodes++;
                    resetDict.run();
                    bestRatio = 0.0;

                    int next = in.read();
                    if (next == -1) {
                        return;
                    }
                    prefix = next;
                    bytesIn++;
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
        System.exit(1);
    }
}
