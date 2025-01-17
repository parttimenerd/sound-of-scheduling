package me.bechberger.sos;

import me.bechberger.ebpf.bpf.map.BPFHashMap;
import me.bechberger.sos.scheduler.BaseScheduler;
import me.bechberger.sos.util.ProcessSet;

import java.util.*;

public class ScoredProcesses {

    private final int keptStats;

    public record SingleStat(long runtimeNs, long dispatches) {}

    public static class ProcessInfo {
        public final int pid;
        final int keptStats;
        private BaseScheduler.TaskStat lastStat;
        private List<SingleStat> lastStats;
        private SingleStat combinedStat;
        private boolean changedRuntimeSinceLastCheck = false;
        private boolean changedDispatchesSinceLastCheck = false;

        private long runtimeDiff = 0;
        private long dispatchesDiff = 0;

        private boolean invalid = false;

        ProcessInfo(int pid, int keptStats, BaseScheduler.TaskStat lastStat) {
            this.pid = pid;
            this.keptStats = keptStats;
            this.lastStat = lastStat;
            this.lastStats = new ArrayList<>();
            this.combinedStat = new SingleStat(0, 0);
        }

        void update(BaseScheduler.TaskStat newStat) {
            if (runtimeDiff == newStat.runtimeNs - lastStat.runtimeNs) {
                runtimeDiff = 0;
                dispatchesDiff = 0;
                changedDispatchesSinceLastCheck = false;
                changedRuntimeSinceLastCheck = false;
                invalid = true;
                return;
            }
            runtimeDiff = newStat.runtimeNs - lastStat.runtimeNs;
            dispatchesDiff = newStat.dispatches - lastStat.dispatches;
            changedRuntimeSinceLastCheck = runtimeDiff > 0;
            changedDispatchesSinceLastCheck = dispatchesDiff > 0;
            lastStats.add(new SingleStat(runtimeDiff, dispatchesDiff));
            if (lastStats.size() > keptStats) {
                SingleStat removed = lastStats.remove(0);
                combinedStat = new SingleStat(combinedStat.runtimeNs - removed.runtimeNs, combinedStat.dispatches - removed.dispatches);
            }
            combinedStat = new SingleStat(combinedStat.runtimeNs + runtimeDiff, combinedStat.dispatches + dispatchesDiff);
            lastStat = newStat;
        }

        public boolean invalid() {
            return invalid;
        }

        public String comm() {
            return lastStat.comm;
        }

        public boolean changedRuntimeSinceLastCheck() {
            return changedRuntimeSinceLastCheck;
        }

        public boolean changedDispatchesSinceLastCheck() {
            return changedDispatchesSinceLastCheck;
        }

        public SingleStat combinedStat() {
            return combinedStat;
        }

        public int dispatchesInTimeSlice() {
            return (int) dispatchesDiff;
        }

        public long runtimeInTimeSlice() {
            return runtimeDiff;
        }
    }

    Map<Integer, ProcessInfo> processInfos = new HashMap<>();

    public ScoredProcesses(int keptStats) {
        this.keptStats = keptStats;
    }

    public void update(int pid, BaseScheduler.TaskStat newStat) {
        if (ProcessHandle.of(pid).flatMap(p -> p.info().command()).isEmpty()) {
            processInfos.remove(pid);
            return;
        }
        if (!processInfos.containsKey(pid)) {
            processInfos.put(pid, new ProcessInfo(pid, keptStats, newStat));
        } else {
            var info = processInfos.get(pid);
            info.update(newStat);
            if (info.invalid()) {
                processInfos.remove(pid);
            }
        }
    }

    public void update(BPFHashMap<Integer, BaseScheduler.TaskStat> stats, ProcessSet filter) {
        var availablePids = new HashSet<>();
        for (var entry : stats.entrySet()) {
            var stat = entry.getValue();
            if (filter.contains(entry.getKey())) {
                update(entry.getKey(), stat);
            }
            availablePids.add(entry.getKey());
        }
        processInfos.keySet().removeIf(pid -> !availablePids.contains(pid));
    }

    /** Get the processes that run the most in the sliding window */
    public List<Integer> getMostRunProcessesSortedDescendingly(int count) {
        return processInfos.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().combinedStat.runtimeNs, e1.getValue().combinedStat.runtimeNs))
                .limit(count)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Get the processes that are dispatched the most in the sliding window */
    public List<Integer> getMostDispatchedProcessesSortedDescendingly(int count) {
        return processInfos.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().combinedStat.dispatches, e1.getValue().combinedStat.dispatches))
                .limit(count)
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean changedRuntimeSinceLastCheck(int pid) {
        return processInfos.containsKey(pid) && processInfos.get(pid).changedRuntimeSinceLastCheck();
    }

    public boolean changedDispatchesSinceLastCheck(int pid) {
        return processInfos.containsKey(pid) && processInfos.get(pid).changedDispatchesSinceLastCheck();
    }

    public long getRuntimeInTimeSlice(int pid) {
        return processInfos.containsKey(pid) ? processInfos.get(pid).runtimeInTimeSlice() : 0;
    }

    public int getDispatchesInTimeSlice(int pid) {
        return processInfos.containsKey(pid) ? processInfos.get(pid).dispatchesInTimeSlice() : 0;
    }
}
