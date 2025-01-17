package me.bechberger.sos.scheduler;

import me.bechberger.ebpf.annotations.Size;
import me.bechberger.ebpf.annotations.Type;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPFFunction;
import me.bechberger.ebpf.annotations.bpf.BPFInterface;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.Scheduler;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.ebpf.runtime.TaskDefinitions;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.bpf.Scheduler.PerProcessFlags.PF_KTHREAD;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_bpf_nr_cpu_ids;
import static me.bechberger.sos.util.DurationConverter.nanoSecondsToString;

@BPFInterface
public interface BaseScheduler extends Scheduler, AutoCloseable {

    /**
     * Settings for the scheduler
     *
     * @param sliceLength      in ns used for scheduling
     * @param cores            number of cores to schedule to (assuming contiguous core ids)
     * @param scaleSliceLength if true, the slice length is scaled by the number of tasks in the queue
     */
    @Type
    record SchedulerSetting(@Unsigned int sliceLength, @Unsigned int cores, boolean scaleSliceLength) {
    }

    static final int COMM_LENGTH = 40;

    @Type
    class TaskStat {
        @Size(COMM_LENGTH)
        public String comm;
        @Unsigned
        public long dispatches;
        @Unsigned
        public long runtimeNs;
        boolean currentlyRunning;
        @Unsigned
        long lastStartNs;
        boolean ignored;

        @Override
        public String toString() {
            return "Stat{" + comm + ", runtime " + nanoSecondsToString(runtimeNs, 3) + ", dispatches " + dispatches + (currentlyRunning ? ", running" : "") + (ignored ? ", ignored" : "") + "}";
        }
    }

    @BPFFunction
    default boolean hasConstraints(Ptr<TaskDefinitions.task_struct> p) {
        return ((p.val().flags & PF_KTHREAD) != 0) || (p.val().nr_cpus_allowed != scx_bpf_nr_cpu_ids());
    }

    void setSetting(SchedulerSetting setting);

    BPFHashMap<@Unsigned Integer, TaskStat> getTaskStats();

    default void tracePrintLoop() {
        if (this instanceof BPFProgram program) {
            program.tracePrintLoop();
        }
    }
}
