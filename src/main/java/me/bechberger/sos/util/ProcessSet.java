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
        if (filterWords.isEmpty()) {
            filteredIds.clear();
            filteredIds.addAll(ProcessHandle.allProcesses().map(ProcessHandle::pid).map(Long::intValue).collect(Collectors.toSet()));
            return;
        }
        filteredIds.clear();
        var startProcesses = ProcessHandle.allProcesses().filter(p -> {
            if (filterWords.isEmpty()) {
                return true;
            }
            return filterWords.stream().anyMatch(f -> p.info().command().orElse("").contains(f));
        }).toList();
        Set<ProcessHandle> children = new HashSet<>();
        for (ProcessHandle process : startProcesses) {
            if (!children.contains(process)) {
                getAllChildProcesses(process, children);
            }
        }
        filteredIds.addAll(startProcesses.stream().map(ProcessHandle::pid).map(Long::intValue).collect(Collectors.toSet()));
        filteredIds.addAll(children.stream().map(ProcessHandle::pid).map(Long::intValue).collect(Collectors.toSet()));
    }

    private static void getAllChildProcesses(ProcessHandle parent, Set<ProcessHandle> children) {
        parent.children().forEach(p -> {
            if (children.add(p)) {
                getAllChildProcesses(p, children);
            }
        });
    }

    public boolean contains(int pid) {
        return filteredIds.contains(pid);
    }
}
