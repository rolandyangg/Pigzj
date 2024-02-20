import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.CheckedInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Pigzj 
{
    public final static int BLOCK_SIZE = 1024 * 128; // 128 KiB
    public final static int DICT_SIZE = 1024 * 32; // 32 KiB

    public Pigzj(String[] args) {
        int numProcessors = parseProcessors(args); 
        compressFile(numProcessors);
    }

    /**
     * Parses the options for the number of processors the user specifies.
     * @param args
     * @return Number of processors the user specifies in the options or number of processors the system has available otherwise
     */
    public int parseProcessors(String[] args) {
        int numProcessors = Runtime.getRuntime().availableProcessors(); // Default number of processors

        for (int i = 0; i < args.length; i++) {
            // Parse the option -p
            if (args[i].equals("-p")) {
                if (i != args.length - 1) {
                    try {
                        int input = Integer.parseInt(args[i + 1]);
                        if (input <= 0) {
                            System.err.println("ERROR: -p Value must be positive");
                            System.exit(1);
                        } else if (input > (4 * numProcessors)) {
                            System.err.println("ERROR: Pigzj need not and should not support attempts to use more than four times the number of available processors.");
                            System.exit(1);
                        }
                        numProcessors = input;
                    }
                    catch (NumberFormatException e) {
                        // ERROR: Was not an integer
                        System.err.println("ERROR: -p Value must be an Integer");
                        System.exit(1);
                    }
                } else {
                    // ERROR: Didn't supply a value after -p
                    System.err.println("ERROR: -p Must supply a value");
                    System.exit(1);
                }
            }
        }

        return numProcessors;
    }
    
    /**
     * Uses multithreading to compress the file and output it in a compressed form with numProcessors number of threads.
     * @param numProcessors
     */
    public void compressFile(int numProcessors) {
        try {
            CRC32 crc = new CRC32(); // RFC 1952 compliant Checksum
            crc.reset();
            CheckedInputStream inputStream = new CheckedInputStream(System.in, crc);

            // Header
            byte[] default_header = new byte[]{31, -117, 8, 0, 0, 0, 0, 0, 0, -1};
            System.out.write(default_header);
            if (System.out.checkError()) {
                System.err.println("ERROR: Output error, check to see if output is writable");
                System.exit(1);
            }

            // Compress data
            int input_length = 0; // Total length of the inputted bytes
            int read_length = 0; // Number of bytes read in each iteration
            byte[] input_bytes = new byte[BLOCK_SIZE]; // Buffer to hold read in bytes
            byte[] dictionary = new byte['\u8000']; // Dictionary fed into deflater for pattern matching optimization, \u8000 Default for first block
            ExecutorService executor = Executors.newFixedThreadPool(numProcessors);
            ArrayList<Future<byte[]>> compressed_blocks = new ArrayList<Future<byte[]>>(); // Hold compressed data blocks

            while ((read_length = inputStream.read(input_bytes)) != -1) {
                input_length += read_length;

                // Handle Last Block
                if (read_length < BLOCK_SIZE) {
                    byte[] last_block_bytes = new byte[read_length];
                    System.arraycopy(input_bytes, 0, last_block_bytes, 0, read_length);
                    input_bytes = last_block_bytes;
                }

                Callable<byte[]> thread = new CompressorThread(input_bytes, dictionary);
                compressed_blocks.add(executor.submit(thread));

                if (read_length < BLOCK_SIZE) break; // Don't execute an extra read or prep for next block if not necessary...

                // Write to the next dictionary the last 32 KiB of the block for the next block
                if (read_length == BLOCK_SIZE) {
                    dictionary = new byte[DICT_SIZE];
                    System.arraycopy(input_bytes, BLOCK_SIZE - DICT_SIZE, dictionary, 0, DICT_SIZE);
                }

                input_bytes = new byte[BLOCK_SIZE]; // Make new buffer so threads not all accessing same buffer at same time
            }

            // Output compressed data
            for (int i = 0; i < compressed_blocks.size(); i++) {
                byte[] compressed_bytes = compressed_blocks.get(i).get();
                System.out.write(compressed_bytes, 0, compressed_bytes.length);
            }

            // Footer
            byte[] footer = new byte[8];
            writeInt((int) inputStream.getChecksum().getValue(), footer, 0); // Checksum
            writeInt((int) input_length, footer, 4); // Uncompressed size
            System.out.write(footer);

            // Close streams
            executor.shutdown();
            inputStream.close();
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }
    }

    // Helper Functions from MessAdmin
    /**
     * Writes out an integer in byte form.
     * @param var0
     * @param var1
     * @param var2
     * @throws IOException
     */
    private static void writeInt(int var0, byte[] var1, int var2) throws IOException { 
        writeShort(var0 & '\uffff', var1, var2);
        writeShort(var0 >> 16 & '\uffff', var1, var2 + 2);
    }

    /**
     * Writes out a short in byte form.
     * @param var0
     * @param var1
     * @param var2
     * @throws IOException
     */
    private static void writeShort(int var0, byte[] var1, int var2) throws IOException {
        var1[var2] = (byte)(var0 & 255);
        var1[var2 + 1] = (byte)(var0 >> 8 & 255);
    }

    public static void main(String[] args) {
        new Pigzj(args);
    }
}

class CompressorThread implements Callable<byte[]> 
{
    public final static int BLOCK_SIZE = 1024 * 128; // 128 KiB
    public byte[] input_bytes;
    public byte[] dictionary_bytes;

    public CompressorThread(byte[] input_bytes, byte[] dictionary_bytes) {
        this.input_bytes = input_bytes;
        this.dictionary_bytes = dictionary_bytes;
    }

    public byte[] call() {
        byte[] temp_buffer = new byte[input_bytes.length];

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setDictionary(dictionary_bytes);
        deflater.setInput(input_bytes);

        if (input_bytes.length < BLOCK_SIZE) 
            deflater.finish(); // This could be screwed if the last block is exactly the size of a single block

        int compressedDataLength = deflater.deflate(temp_buffer, 0, temp_buffer.length, Deflater.SYNC_FLUSH);
        deflater.end();

        // Copy compressed data over into a smaller buffer
        byte[] output_bytes = new byte[compressedDataLength];
        System.arraycopy(temp_buffer, 0, output_bytes, 0, compressedDataLength);

        return output_bytes;
    }
}