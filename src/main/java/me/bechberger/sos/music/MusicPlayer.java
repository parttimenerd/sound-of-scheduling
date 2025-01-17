package me.bechberger.sos.music;

import me.bechberger.sos.Main;
import me.bechberger.sos.ScoredProcesses;
import me.bechberger.sos.util.ProcessSet;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static me.bechberger.sos.util.DurationConverter.nanoSecondsToString;

public class MusicPlayer implements AutoCloseable {

    public static final int MIN_NOTE = 40;
    public static final int MAX_NOTE = 90;

    @FunctionalInterface
    interface ProcessAndNoteConsumer {
        void accept(int process, int note);
    }

    /**
     * Idea: A process keeps it's note as long as it is still in the list of live processes
     * <p>
     * Later on play note if the process has increased runtime since last check
     */
    static class MusicToNote {

        private final Scale scale;

        private final Map<Integer, Integer> noteMap;
        private final Set<Integer> availableNotes;

        public MusicToNote(Scale scale) {
            this.scale = scale;
            this.noteMap = new HashMap<>();
            this.availableNotes = new HashSet<>();
            for (int i = 0; i < scale.length(); i++) {
                availableNotes.add(scale.get(i));
            }
        }

        /** Returns the notes that changed owners */
        public Set<Integer> update(List<Integer> liveProcesses) {
            Set<Integer> processesToRemove = noteMap.keySet().stream().filter(p -> !liveProcesses.contains(p)).collect(Collectors.toSet());
            var notesThatChangedOwners = new HashSet<Integer>();
            for (Integer process : processesToRemove) {
                availableNotes.add(noteMap.remove(process));
                noteMap.remove(process);
                notesThatChangedOwners.add(process);
            }
            List<Integer> processesToAdd = liveProcesses.stream().filter(p -> !noteMap.containsKey(p)).toList();

            // take the len(processesToAdd) notes from availableNotes that are closest to the middle of the scale
            List<Integer> notesClosesToCenter = availableNotes.stream().sorted(Comparator.comparingInt(n -> Math.abs(n - scale.getCenter()))).toList();

            for (int i = 0; i < processesToAdd.size(); i++) {
                noteMap.put(processesToAdd.get(i), notesClosesToCenter.get(i));
                availableNotes.remove(notesClosesToCenter.get(i));
            }
            return notesThatChangedOwners;
        }

        public int get(int process) {
            return noteMap.get(process);
        }

        public void forEach(ProcessAndNoteConsumer consumer) {
            noteMap.forEach(consumer::accept);
        }

        public Set<Integer> getProcesses() {
            return noteMap.keySet();
        }
    }

    private final Scale scale;
    /** Instrument for the processes with the longest runtime */
    private final Instrument runtimeInstrument;
    /** Instrument for the processes with the most dispatches */
    private final Instrument dispatchesInstrument;

    private final ScoredProcesses scoredProcesses;

    private final MusicToNote runtimeMusicToNote;
    private final MusicToNote dispatchesMusicToNote;

    private final long intervalNs;

    private Synthesizer synthesizer;
    private MidiChannel[] channels;

    private Set<Integer> currentlyEnabledRuntimeNotes = new HashSet<>();

    public MusicPlayer(Scale scale, Instrument runtimeInstrument, Instrument dispatchesInstrument, ScoredProcesses scoredProcesses, long intervalNs) {
        this.scale = scale;
        this.runtimeInstrument = runtimeInstrument;
        this.dispatchesInstrument = dispatchesInstrument;
        this.scoredProcesses = scoredProcesses;
        this.runtimeMusicToNote = new MusicToNote(scale);
        this.dispatchesMusicToNote = new MusicToNote(scale);
        this.intervalNs = intervalNs;

        try {
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
        } catch (MidiUnavailableException e) {
            throw new RuntimeException(e);
        }
        channels = synthesizer.getChannels();
        channels[0].programChange(runtimeInstrument.getId()); // channel 0 is for the runtime instrument
        channels[1].programChange(dispatchesInstrument.getId()); // channel 1 is for the dispatches instrument
    }

    public void update() {
        var maxLoudness = updateRuntimeNotes();
        updateDispatchesNotes(maxLoudness);
    }

    private int updateRuntimeNotes() {
        var mostRun = scoredProcesses.getMostDispatchedProcessesSortedDescendingly(scale.length());
        var notesThatChangedOwners = runtimeMusicToNote.update(mostRun);
        // disable notes that changed owners
        for (Integer note : notesThatChangedOwners) {
            channels[0].noteOff(note);
            currentlyEnabledRuntimeNotes.remove(note);
        }
        // enable notes that are still in the list of top processes
        // scale relative to the max runtime
        AtomicInteger maxLoudness = new AtomicInteger(0);
        var checkedNotes = new HashSet<Integer>();
        runtimeMusicToNote.forEach((process, note) -> {
            checkedNotes.add(note);
            if (!scoredProcesses.changedRuntimeSinceLastCheck(process)) {
                channels[0].noteOff(note);
                currentlyEnabledRuntimeNotes.remove(note);
                return;
            }
            var loudness = (int) (Math.min(127, 127 * scoredProcesses.getRuntimeInTimeSlice(process) / intervalNs));
            channels[0].noteOn(note, loudness);
            maxLoudness.set(Math.max(maxLoudness.get(), loudness));
            currentlyEnabledRuntimeNotes.add(note);
            System.out.println("Enabling note " + note + " for process " + process + " with loudness " + loudness + " (" + ProcessHandle.of(process).flatMap(p -> p.info().command()).orElse("") + ") " + nanoSecondsToString(scoredProcesses.getRuntimeInTimeSlice(process), 3));
        });
        for (int i = 0; i < scale.length(); i++) {
            var note = scale.get(i);
            if (checkedNotes.contains(note)) {
                continue;
            }
            channels[0].noteOff(note);
        }
        return maxLoudness.get();
    }

    private void updateDispatchesNotes(int maxLoudness) {
        dispatchesMusicToNote.update(scoredProcesses.getMostRunProcessesSortedDescendingly(scale.length()));

        // idea: toggle every note off
        // then toggle on every note that is

        // disable notes that changed owners
        for (int i = 0; i < scale.length(); i++) {
            channels[1].noteOff(scale.get(i));
        }
        // enable notes that are mapped to a process that was dispatched
        // scale by the number of dispatches (max is 127 and is reserved for the max dispatches)
        int maxDispatches = dispatchesMusicToNote.getProcesses().stream().mapToInt(scoredProcesses::getDispatchesInTimeSlice).max().orElse(0);
        if (maxDispatches == 0) {
            return;
        }
        dispatchesMusicToNote.forEach((process, note) -> {
            channels[1].noteOn(note, maxLoudness * scoredProcesses.getDispatchesInTimeSlice(process) / maxDispatches);
        });
    }

    @Override
    public void close() {
        synthesizer.close();
    }
}
