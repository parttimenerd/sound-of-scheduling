package me.bechberger.sos.music;

/**
 * Midi Instrument
 */
public enum Instrument {
    ACOUSTIC_GRAND_PIANO(0),
    BRIGHT_ACOUSTIC_PIANO(1),
    ELECTRIC_GRAND_PIANO(2),
    HORNS(60),
    SYNTHESIZER(80),
    BANJO(105),
    CELESTA(9),
    GLOCKENSPIEL(10),
    MUSIC_BOX(11),
    VIBRAPHONE(12),
    MARIMBA(13),
    XYLOPHONE(14),
    TUBULAR_BELLS(15),
    DULCIMER(16),
    DRAWBAR_ORGAN(17),
    PERCUSSIVE_ORGAN(18),
    ROCK_ORGAN(19),
    CHURCH_ORGAN(20),
    REED_ORGAN(21),
    ACCORDION(22),
    HARMONICA(23),
    TANGO_ACCORDION(24),
    VIOLIN(41),
    VIOLA(42),
    CELLO(43),
    CONTRABASS(44),
    TRUMPET(57),
    TROMBONE(58),
    TUBA(59),
    PICCOLO(73),
    FLUTE(74),
    RECORDER(75),
    PAN_FLUTE(76),
    BLOWN_BOTTLE(77),
    SHAKUHACHI(78),
    WHISTLE(79),
    OCARINA(80);
    private final int id;

    Instrument(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
