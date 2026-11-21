package com.misanthropy.thread_priorities;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class Win {
    interface K32 extends Library {
        Pointer CreateToolhelp32Snapshot(int flags, int pid);

        boolean Thread32First(Pointer snapshot, Pointer entry);

        boolean Thread32Next(Pointer snapshot, Pointer entry);

        Pointer OpenThread(int access, boolean inherit, int tid);

        int GetThreadDescription(Pointer thread, PointerByReference description);

        Pointer LocalFree(Pointer memory);

        int GetThreadPriority(Pointer thread);

        boolean SetThreadPriority(Pointer thread, int priority);

        boolean CloseHandle(Pointer handle);

        int GetCurrentProcessId();

        int GetCurrentThreadId();

        Pointer GetCurrentProcess();

        boolean GetModuleHandleExW(int flags, Pointer address, PointerByReference module);

        boolean K32GetModuleInformation(Pointer process, Pointer module, Pointer info, int size);

        int GetModuleFileNameW(Pointer module, char[] name, int size);
    }

    interface Nt extends Library {
        int NtQueryInformationThread(Pointer thread, int infoClass, PointerByReference value, int length, Pointer returned);
    }

    static final int NORMAL = 0;
    static final int HIGHEST = 2;
    static final int TIME_CRITICAL = 15;
    static final int OFF = 99;
    private static final String[] BACKGROUND = {"C1 CompilerThread", "C2 CompilerThread", "G1 Conc", "G1 Refine", "Sweeper thread"};

    private static K32 k32;
    private static Nt nt;
    private static int pid;
    private static volatile long driverBase;
    private static volatile long driverEnd;
    static volatile String driverName = "";
    private static final Set<Integer> done = new HashSet<>();
    private static final String[] INPUT = {"Ixeris Event Polling Thread", "Sound engine"};
    private static final Map<Integer, Integer> ours = new HashMap<>();
    private static int lastDriver = OFF;
    private static boolean lastCritical;
    static int inputMoved;
    static int driverMoved;
    static int backgroundMoved;

    private Win() {
    }

    static boolean init() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return false;
        }
        try {
            k32 = Native.load("kernel32", K32.class);
            nt = Native.load("ntdll", Nt.class);
            pid = k32.GetCurrentProcessId();
            Pointer self = k32.OpenThread(0x0860, false, k32.GetCurrentThreadId());
            if (self == null) {
                return false;
            }
            try {
                describe(self);
                startAddress(self);
            } finally {
                k32.CloseHandle(self);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void driverFunction(long address) {
        if (k32 == null || address == 0L || driverEnd != 0L) {
            return;
        }
        try {
            PointerByReference module = new PointerByReference();
            if (!k32.GetModuleHandleExW(4, new Pointer(address), module) || module.getValue() == null) {
                return;
            }
            Memory info = new Memory(24);
            if (!k32.K32GetModuleInformation(k32.GetCurrentProcess(), module.getValue(), info, 24)) {
                return;
            }
            char[] name = new char[260];
            int length = k32.GetModuleFileNameW(module.getValue(), name, name.length);
            String path = new String(name, 0, Math.max(0, length));
            driverName = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
            long base = Pointer.nativeValue(info.getPointer(0));
            driverBase = base;
            driverEnd = base + (info.getInt(8) & 0xFFFFFFFFL);
            synchronized (done) {
                done.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean current(int priority) {
        if (k32 == null) {
            return false;
        }
        Pointer self = k32.OpenThread(0x0860, false, k32.GetCurrentThreadId());
        if (self == null) {
            return false;
        }
        try {
            return k32.SetThreadPriority(self, priority);
        } finally {
            k32.CloseHandle(self);
        }
    }

    private static boolean invalid(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == -1L;
    }

    static void scan(int driverPriority, int backgroundPriority, boolean critical) {
        Pointer snapshot = k32.CreateToolhelp32Snapshot(4, 0);
        if (invalid(snapshot)) {
            return;
        }
        Set<Integer> live = new HashSet<>();
        synchronized (done) {
            if (driverPriority != lastDriver || critical != lastCritical) {
                lastDriver = driverPriority;
                lastCritical = critical;
                done.clear();
            }
            try {
                Memory entry = new Memory(28);
                entry.setInt(0, 28);
                boolean more = k32.Thread32First(snapshot, entry);
                while (more) {
                    if (entry.getInt(12) == pid) {
                        int tid = entry.getInt(8);
                        live.add(tid);
                        if (!done.contains(tid)) {
                            handle(tid, driverPriority, backgroundPriority, critical);
                        }
                    }
                    more = k32.Thread32Next(snapshot, entry);
                }
            } finally {
                k32.CloseHandle(snapshot);
            }
            done.retainAll(live);
            ours.keySet().retainAll(live);
        }
    }

    private static void handle(int tid, int driverPriority, int backgroundPriority, boolean critical) {
        Pointer thread = k32.OpenThread(0x0860, false, tid);
        if (thread == null) {
            done.add(tid);
            return;
        }
        try {
            String name = describe(thread);
            if (name.isEmpty()) {
                long end = driverEnd;
                if (end == 0L) {
                    return;
                }
                long start = startAddress(thread);
                if (start < driverBase || start >= end) {
                    return;
                }
                if (adjust(tid, thread, driverPriority == OFF ? OFF : critical ? TIME_CRITICAL : driverPriority, true)) {
                    driverMoved++;
                }
            } else if (input(name)) {
                if (adjust(tid, thread, critical ? TIME_CRITICAL : OFF, false)) {
                    inputMoved++;
                }
            } else if (backgroundPriority != OFF && background(name) && k32.GetThreadPriority(thread) == HIGHEST && k32.SetThreadPriority(thread, backgroundPriority)) {
                backgroundMoved++;
            }
            done.add(tid);
        } finally {
            k32.CloseHandle(thread);
        }
    }

    private static boolean adjust(int tid, Pointer thread, int priority, boolean untouchedOnly) {
        int now = k32.GetThreadPriority(thread);
        Integer original = ours.get(tid);
        if (priority == OFF) {
            if (original != null && now != original) {
                k32.SetThreadPriority(thread, original);
            }
            return false;
        }
        if (now == priority || original == null && untouchedOnly && now != NORMAL) {
            return false;
        }
        if (!k32.SetThreadPriority(thread, priority) || original != null) {
            return false;
        }
        ours.put(tid, now);
        return true;
    }

    private static boolean input(String name) {
        for (String prefix : INPUT) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean background(String name) {
        for (String prefix : BACKGROUND) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static long startAddress(Pointer thread) {
        PointerByReference value = new PointerByReference();
        if (nt.NtQueryInformationThread(thread, 9, value, 8, null) != 0) {
            return 0L;
        }
        return Pointer.nativeValue(value.getValue());
    }

    private static String describe(Pointer thread) {
        PointerByReference ref = new PointerByReference();
        if (k32.GetThreadDescription(thread, ref) < 0) {
            return "";
        }
        Pointer text = ref.getValue();
        if (text == null) {
            return "";
        }
        try {
            return text.getWideString(0);
        } finally {
            k32.LocalFree(text);
        }
    }
}
