package me.bechberger.sos.scheduler;

import me.bechberger.ebpf.annotations.AlwaysInline;
import me.bechberger.ebpf.annotations.Unsigned;
import me.bechberger.ebpf.annotations.bpf.BPF;
import me.bechberger.ebpf.annotations.bpf.BPFFunction;
import me.bechberger.ebpf.annotations.bpf.BPFMapDefinition;
import me.bechberger.ebpf.annotations.bpf.Property;
import me.bechberger.ebpf.bpf.BPFJ;
import me.bechberger.ebpf.bpf.BPFProgram;
import me.bechberger.ebpf.bpf.GlobalVariable;
import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.ebpf.bpf.map.BPFLRUHashMap;
import me.bechberger.ebpf.runtime.BpfDefinitions;
import me.bechberger.ebpf.runtime.TaskDefinitions;
import me.bechberger.ebpf.type.Ptr;

import static me.bechberger.ebpf.runtime.BpfDefinitions.bpf_cpumask_test_cpu;
import static me.bechberger.ebpf.runtime.ScxDefinitions.*;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_dsq_id_flags.SCX_DSQ_LOCAL_ON;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_enq_flags.SCX_ENQ_PREEMPT;
import static me.bechberger.ebpf.runtime.ScxDefinitions.scx_public_consts.SCX_SLICE_DFL;
import static me.bechberger.ebpf.runtime.helpers.BPFHelpers.bpf_ktime_get_ns;

/**
 * vtime based scheduler, based on the <a href="https://github.com/parttimenerd/hello-ebpf/blob/a4169c79bcef25e7372c199230a78593f308166a/bpf-samples/src/main/java/me/bechberger/ebpf/samples/SampleScheduler.java">SampleScheduler</a>.
 */
@BPF(license = "GPL")
@Property(name = "sched_name", value = "fifo_soc_scheduler")
public abstract class VTimeScheduler extends BPFProgram implements BaseScheduler {

    private static final int SHARED_DSQ_ID = 0;

    final GlobalVariable<SchedulerSetting> schedulerSetting = new GlobalVariable<>(new SchedulerSetting(1,1, true));

    @BPFMapDefinition(maxEntries = 100000)
    BPFLRUHashMap<@Unsigned Integer, TaskStat> taskStats;

    @BPFFunction
    @AlwaysInline
    void getTaskStat(Ptr<TaskDefinitions.task_struct> task, Ptr<Ptr<TaskStat>> statPtr) {
        var id = task.val().tgid;
        var ret = taskStats.bpf_get(id);
        if (ret == null) {
            var stat = new TaskStat();
            stat.runtimeNs = 0;
            stat.currentlyRunning = false;
            stat.dispatches = 0;
            stat.ignored = hasConstraints(task);
            BPFJ.bpf_probe_read_kernel_str(stat.comm, task.val().comm);
            taskStats.put(id, stat);
        }
        var ret2 = taskStats.bpf_get(id);
        statPtr.set(ret2);
    }

    final GlobalVariable<@Unsigned Long> vtime_now = new GlobalVariable<>(0L);

    @BPFFunction
    @AlwaysInline
    boolean isSmaller(@Unsigned long a, @Unsigned long b) {
        return (long)(a - b) < 0;
    }

    @Override
    public int init() {
        return scx_bpf_create_dsq(SHARED_DSQ_ID, -1);
    }

    @Override
    public void enqueue(Ptr<TaskDefinitions.task_struct> p, long enq_flags) {
        @Unsigned int sliceLength = schedulerSetting.get().sliceLength();
        if (schedulerSetting.get().scaleSliceLength()) {
            sliceLength = sliceLength / scx_bpf_dsq_nr_queued(SHARED_DSQ_ID);
        }

        @Unsigned long vtime = p.val().scx.dsq_vtime;

        /*
         * Limit the amount of budget that an idling task can accumulate
         * to one slice.
         */
        if (isSmaller(vtime, vtime_now.get() - SCX_SLICE_DFL.value())) {
            vtime = vtime_now.get() - SCX_SLICE_DFL.value();
        }
        scx_bpf_dsq_insert_vtime(p, SHARED_DSQ_ID, SCX_SLICE_DFL.value(), vtime, enq_flags);
    }

    @BPFFunction
    @AlwaysInline
    public boolean tryDispatching(Ptr<BpfDefinitions.bpf_iter_scx_dsq> iter, Ptr<TaskDefinitions.task_struct> p, int cpu) {
        // check if the CPU is usable by the task
        if (!bpf_cpumask_test_cpu(cpu, p.val().cpus_ptr)) {
            return false;
        }
        return scx_bpf_dsq_move(iter, p, SCX_DSQ_LOCAL_ON.value() | cpu, SCX_ENQ_PREEMPT.value());
    }

    @Override
    public void dispatch(int cpu, Ptr<TaskDefinitions.task_struct> prev) {
        boolean canScheduleNonKThreads = schedulerSetting.get().cores() != -1 && schedulerSetting.get().cores() > cpu;
        Ptr<TaskDefinitions.task_struct> p = null;
        bpf_for_each_dsq(SHARED_DSQ_ID, p, iter -> {
            if ((hasConstraints(p) || canScheduleNonKThreads) && tryDispatching(iter, p, cpu)) {
                return;
            }
        });
    }

    @Override
    public void running(Ptr<TaskDefinitions.task_struct> p) {
        /*
         * Global vtime always progresses forward as tasks start executing. The
         * test and update can be performed concurrently from multiple CPUs and
         * thus racy. Any error should be contained and temporary. Let's just
         * live with it.
         */
        @Unsigned long vtime = p.val().scx.dsq_vtime;
        if (isSmaller(vtime_now.get(), vtime)) {
            vtime_now.set(vtime);
        }
        Ptr<TaskStat> stat = null;
        getTaskStat(p, Ptr.of(stat));
        if (stat != null) {
            stat.val().currentlyRunning = true;
            stat.val().dispatches = stat.val().dispatches + 1;
            stat.val().lastStartNs = bpf_ktime_get_ns();
        }
    }

    @Override
    public void stopping(Ptr<TaskDefinitions.task_struct> p, boolean runnable) {
        /*
         * Scale the execution time by the inverse of the weight and charge.
         *
         * Note that the default yield implementation yields by setting
         * @p->scx.slice to zero and the following would treat the yielding task
         * as if it has consumed all its slice. If this penalizes yielding tasks
         * too much, determine the execution time by taking explicit timestamps
         * instead of depending on @p->scx.slice.
         */
        Ptr<TaskStat> stat = null;
        getTaskStat(p, Ptr.of(stat));
        if (stat == null) {
            return;
        }
        stat.val().currentlyRunning = false;
        @Unsigned long runtimeNs = bpf_ktime_get_ns() - stat.val().lastStartNs;
        stat.val().runtimeNs = stat.val().runtimeNs + runtimeNs;

        p.val().scx.dsq_vtime += runtimeNs / p.val().scx.weight;

    }

    @Override
    public void enable(Ptr<TaskDefinitions.task_struct> p) {
        p.val().scx.dsq_vtime = vtime_now.get();
    }

    @Override
    public void setSetting(SchedulerSetting setting) {
        schedulerSetting.set(setting);
    }

    @Override
    public BPFHashMap<@Unsigned Integer, TaskStat> getTaskStats() {
        return taskStats;
    }
}