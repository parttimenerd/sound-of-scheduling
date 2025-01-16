package me.bechberger.sos.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Set of filtered processes */
public class ProcessSet {

    private final List<String> filterWords;
    private final Set<Integer> filteredIds = new HashSet<>();

    public ProcessSet(List<String> filterWords) {
        this.filterWords = filterWords;
    }

    public void update() {
        filteredIds.clear();
        var startProcesses = ProcessHandle.allProcesses().filter(p -> {
            if (filterWords.isEmpty()) {
                return true;
            }
            return filterWords.stream().anyMatch(f -> p.info().command().orElse("").contains(f));
        }).toList();
        var children = startProcesses.stream().map(ProcessSet::getAllChildProcesses).flatMap(Set::stream).collect(Collectors.toSet());
        filteredIds.addAll(startProcesses.stream().map(ProcessHandle::pid).map(Long::intValue).collect(Collectors.toSet()));
        filteredIds.addAll(children.stream().map(ProcessHandle::pid).map(Long::intValue).collect(Collectors.toSet()));
    }

    private static Set<ProcessHandle> getAllChildProcesses(ProcessHandle parent) {
        Set<ProcessHandle> children = new HashSet<>(parent.children().collect(Collectors.toSet()));
        for (ProcessHandle child : new HashSet<>(children)) {
            children.addAll(getAllChildProcesses(child));  // Recursively find child processes
        }
        return children;
    }

    public boolean contains(int pid) {
        return filteredIds.contains(pid);
    }
}
