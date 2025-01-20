package me.bechberger.sos;

import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.Scheduler;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.ebpf.runtime.runtime;
import me.bechberger.sos.music.Instrument;
import me.bechberger.sos.music.MusicPlayer;
import me.bechberger.sos.music.Scale;
import me.bechberger.sos.scheduler.BaseScheduler;
import me.bechberger.sos.scheduler.FIFOScheduler;
import me.bechberger.sos.scheduler.LotteryScheduler;
import me.bechberger.sos.scheduler.VTimeScheduler;
import me.bechberger.sos.util.DurationConverter;
import me.bechberger.sos.util.ProcessSet;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static me.bechberger.sos.util.DurationConverter.nanoSecondsToString;
import static picocli.CommandLine.Option;

@CommandLine.Command(name = "scheduler.sh", mixinStandardHelpOptions = true,
        description = "Linux scheduler that logs task stats and produces sound")
public class Main implements Runnable{

    enum SchedulerType {
        FIFO(FIFOScheduler.class),
        LOTTERY(LotteryScheduler.class),
        VTIME(VTimeScheduler.class);

        final Class<? extends BaseScheduler> schedulerClass;

        SchedulerType(Class<? extends BaseScheduler> schedulerClass) {
            this.schedulerClass = schedulerClass;
        }
    }

    @Option(names = {"-c", "--cores"}, defaultValue = "-1",
            description = "Number of cores to use, -1 for all cores")
    int cores;

    @Option(names = {"-s", "--slice"}, defaultValue = "5ms",
            description = "Time slice duration", converter = DurationConverter.class)
    int sliceNs;

    @Option(names = {"-a", "--scale-slice"}, defaultValue = "false",
            description = "Scale slice length based on number of tasks")
    boolean scaleSlice;

    @Option(names = {"-t", "--type"}, defaultValue = "FIFO",
            description = "Scheduler type, one of: ${COMPLETION-CANDIDATES}")
    SchedulerType type;

    @Option(names = {"-f", "--filter"}, split = ",", description = "All displayed processes must have one of these substrings in their names," +
            "processes are also included if their parent process matches")
    List<String> filterWords = new ArrayList<>();

    @Option(names = "--bpm", defaultValue = "120", description = "Beats (quarter notes) per minute for the sound")
    int bpm;

    @Option(names = "--window-size", defaultValue = "10", description = "Sliding window size for computing the rankings")
    int windowSize;

    @Option(names = "--scale", defaultValue = "MINOR_PENTATONIC", description = "Musical scale for the sound, one of: ${COMPLETION-CANDIDATES}")
    Scale scale;

    @Option(names = "--runtime-instrument", defaultValue = "MARIMBA", description = "Instrument for the tasks with the most runtime, one of: ${COMPLETION-CANDIDATES}")
    Instrument runtimeInstrument;

    @Option(names = "--dispatches-instrument", defaultValue = "ACOUSTIC_GRAND_PIANO", description = "Instrument for the tasks with the most dispatches")
    Instrument dispatchesInstrument;

    private long intervalNs() {
        return 60000000000L / bpm / 2;
    }
    private ProcessSet filter;
    private ScoredProcesses scoredProcesses;

    private void init() {
        filter = new ProcessSet(this.filterWords);
        scoredProcesses = new ScoredProcesses(windowSize);
    }

    private void iteration(MusicPlayer player, BPFHashMap<Integer, BaseScheduler.TaskStat> stats, boolean firstRound) {
        filter.update();
        // update the scored processes
        scoredProcesses.update(stats, filter);
        if (firstRound) {
            return;
        }
        player.update();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void run() {
        System.out.println("Filter for the process tree: " + filterWords);
        System.out.println("Query interval: " + nanoSecondsToString(intervalNs(), 3));

        try (var program = BPFProgram.load((Class<BPFProgram>) (Class) type.schedulerClass)) {
            var base = (BaseScheduler) program;
            base.setSetting(new BaseScheduler.SchedulerSetting(cores, sliceNs, scaleSlice));
            ((Scheduler)program).attachScheduler();
            init();
            try (var player = new MusicPlayer(scale, runtimeInstrument, dispatchesInstrument, scoredProcesses, intervalNs())) {
                System.out.println("Starting scheduler");
                boolean firstRound = true;
                while (((Scheduler) program).isSchedulerAttachedProperly()) {
                    long start = System.nanoTime();
                    iteration(player, ((BaseScheduler) program).getTaskStats(), firstRound);
                    if (firstRound) {
                        firstRound = false;
                    }
                    long end = System.nanoTime();
                    long sleepTime = intervalNs() - (end - start);
                    System.out.println("Iteration took " + nanoSecondsToString((end - start), 3));
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var cli = new CommandLine(new Main());
        cli.registerConverter(SchedulerType.class, name -> SchedulerType.valueOf(name.toUpperCase()))
                .registerConverter(Scale.class, name -> Scale.valueOf(name.toUpperCase()))
                .registerConverter(Instrument.class, name -> Instrument.valueOf(name.toUpperCase()))
                .setUnmatchedArgumentsAllowed(false)
                .execute(args);
    }

}