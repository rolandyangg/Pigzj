# Pigzj
Multithreaded Java implementation of popular file compressor [gzip](https://www.gzip.org/) (based on [pigz](https://zlib.net/pigz/))

Takes in any input stream of data and outputs RFC 1952 compliant compressed data using Java's Deflator across several threads implemented using Executors, ThreadPools, Callables, and Futures.

Reads in 128 KiB blocks of data in at a time and compresses them, then concatenates them back together. Utilizes strategy of reading last 32 KiB of prior read block to prime compression dictionary for better compression.

This implementation is ~3x as fast as gzip and has compression levels on par with pigz.

## Usage

Input any data into the program and it will output a compressed version. Input may be a stream (not just a file) and output may be redirected into a file.

```bash
java Pigzj.java < input.txt > output.gz
```

```bash
seq 1 9999 | java Pigzj.java > output.gz
```

Currently supports one option (-p) which is to specify the maximum number of processes to be spawned (max number of threads). Supports at most 4x as many processes as there are available processors on a system.

```bash
java Pigzj.java -p 12 < input.txt > output.gz
```

