package me.bechberger.sos;

import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.Scheduler;
import me.bechberger.sos.scheduler.BaseScheduler;
import me.bechberger.sos.scheduler.FIFOScheduler;
import me.bechberger.sos.scheduler.LotteryScheduler;
import me.bechberger.sos.scheduler.VTimeScheduler;
import me.bechberger.sos.util.DurationConverter;
import me.bechberger.sos.util.ProcessSet;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

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

    @Option(names = {"-f", "--filter"}, split = ",", arity = "0..*", description = "All displayed processes must have one of these substrings in their names," +
            "processes are also included if their parent process matches")
    List<String> filterWords = new ArrayList<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void run() {
        System.out.println("Filter for the process tree: " + filterWords);
        ProcessSet filter = new ProcessSet(this.filterWords);
        try (var program = BPFProgram.load((Class<BPFProgram>) (Class) type.schedulerClass)) {
            var base = (BaseScheduler) program;
            base.setSetting(new BaseScheduler.SchedulerSetting(cores, sliceNs, scaleSlice));
            ((Scheduler)program).attachScheduler();
            while (((Scheduler)program).isSchedulerAttachedProperly()) {
                Thread.sleep(1000);
                System.out.println("-----");
                filter.update();
                ((BaseScheduler)program).getTaskStats().forEach((task, stats) -> {
                    if (filter.contains(task)) {
                        System.out.println(task + ": " + stats);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var cli = new CommandLine(new Main());
        cli.registerConverter(SchedulerType.class, name -> SchedulerType.valueOf(name.toUpperCase()))
                .execute(args);
    }

}