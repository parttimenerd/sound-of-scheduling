Sound of Scheduling
===========

A scheduler that produces sound and is written in Java using [hello-ebpf](https://github.com/parttimenerd/hello-ebpf).

## Usage

```sh
./scheduler.sh
```

Or when considering only firefox and slicing the scale:
```
./scheduler.sh --bpm=200 --scale-slice --filter firefox
```

Full usage:
```
Usage: scheduler.sh [-ahV] [--verbose] [--bpm=<bpm>] [-c=<cores>]
                    [--dispatches-instrument=<dispatchesInstrument>]
                    [--runtime-instrument=<runtimeInstrument>] [-s=<sliceNs>]
                    [--scale=<scale>] [-t=<type>] [--window-size=<windowSize>]
                    [-f=<filterWords>[,<filterWords>...]]...
Linux scheduler that logs task stats and produces sound
  -a, --scale-slice       Scale slice length based on number of tasks
      --bpm=<bpm>         Beats (quarter notes) per minute for the sound
  -c, --cores=<cores>     Number of cores to use, -1 for all cores
      --dispatches-instrument=<dispatchesInstrument>
                          Instrument for the tasks with the most dispatches
  -f, --filter=<filterWords>[,<filterWords>...]
                          All displayed processes must have one of these
                            substrings in their names,processes are also
                            included if their parent process matches
  -h, --help              Show this help message and exit.
      --runtime-instrument=<runtimeInstrument>
                          Instrument for the tasks with the most runtime
  -s, --slice=<sliceNs>   Time slice duration
      --scale=<scale>     Musical scale for the sound, one of:
                            MAJOR_PENTATONIC, MINOR_PENTATONIC, BLUES,
                            WHOLE_TONE, HARMONIC_MINOR, MELODIC_MINOR,
                            HARMONIC_MAJOR
  -t, --type=<type>       Scheduler type, one of: FIFO, LOTTERY, VTIME
  -V, --version           Print version information and exit.
      --verbose           Prints more information
      --window-size=<windowSize>
                          Sliding window size for computing the rankings
```

## Install

Install a 6.12 (or later) kernel, on Ubuntu use [mainline](https://github.com/bkw777/mainline) if you're on Ubuntu 24.10 or older.

You should also have installed:

- `libbpf-dev`
- clang
- Java 23

Now you just have to build the sound-of-scheduling via:

```sh
mvn package
```

The code assumes that there is a pulse-audio server running at `/run/user/1000/pulse/native`.

You can speed it up with `mvnd`.

License
=======
GPLv2