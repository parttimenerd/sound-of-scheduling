Sound of Scheduling
===========

A scheduler that produces sound and is written in Java using [hello-ebpf](https://github.com/parttimenerd/hello-ebpf).

## Usage

```sh
./scheduler.sh
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

You can speed it up with `mvnd`.

License
=======
GPLv2