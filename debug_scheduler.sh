#!/usr/bin/sh

sudo -E PATH=$PATH PULSE_SERVER=unix:/run/user/1000/pulse/native zsh -c "java '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005' --enable-native-access=ALL-UNNAMED -cp target/sound-of-scheduling-0.1-SNAPSHOT-jar-with-dependencies.jar me.bechberger.sos.Main $*" -- "$@"
