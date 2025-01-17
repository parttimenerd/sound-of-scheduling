package me.bechberger.sos.music;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static me.bechberger.sos.music.MusicPlayer.MAX_NOTE;
import static me.bechberger.sos.music.MusicPlayer.MIN_NOTE;

/**
 * Musical scales
 */
public enum Scale {
    MAJOR_PENTATONIC(0, 2, 4, 7, 9),
    MINOR_PENTATONIC(0, 3, 5, 7, 10),
    BLUES(0, 3, 5, 6, 7, 10),
    WHOLE_TONE(0, 2, 4, 6, 8, 10),
    HARMONIC_MINOR(0, 2, 3, 5, 7, 8, 11),
    MELODIC_MINOR(0, 2, 3, 5, 7, 9, 11),
    HARMONIC_MAJOR(0, 2, 4, 5, 7, 8, 11); // Added Harmonic Major

    private final List<Integer> notes;

    // Constructor that directly calls generateScale
    Scale(int... scaleSteps) {
        this.notes = generateScale(scaleSteps).stream().filter(note -> note <= MAX_NOTE && note >= MIN_NOTE).toList();
    }

    // Shared logic for generating a scale
    private static List<Integer> generateScale(int[] scaleSteps) {
        int range = MAX_NOTE + 1;
        int numOctaves = (range / 12) + 1; // Number of octaves to cover range
        int[] notes = new int[scaleSteps.length * numOctaves];

        int index = 0;
        for (int octave = 0; octave < numOctaves; octave++) {
            int baseNote = octave * 12;
            for (int step : scaleSteps) {
                int note = baseNote + step;
                if (note <= MAX_NOTE) {
                    notes[index++] = note;
                }
            }
        }
        return Arrays.stream(notes).boxed().toList();
    }

    public int length() {
        return notes.size();
    }

    public int getCenter() {
        return notes.get(notes.size() / 2);
    }

    public int get(int index) {
        return notes.get(index);
    }
}
