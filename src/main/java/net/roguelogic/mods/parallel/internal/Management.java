package net.roguelogic.mods.parallel.internal;

import net.minecraftforge.fml.common.FMLLog;
import net.roguelogic.mods.parallel.api.IThreaded;
import net.roguelogic.mods.parallel.api.MCThread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;

public final class Management {

    public static boolean multithreded = true;

    // Thread Ticking
    private static Thread mainThread;

    private static ArrayList<SubThread> threadsToExecute = new ArrayList<>();

    private static HashSet<MCThread> fullThreads = new HashSet<>();

    private static int threadsDone = 0;

    private static boolean allThreadsDone = true;

    private static boolean mainWaiting = false;
    private static int ticks = 0;
    private static long TT = 0;
    private static long LTT = 0;
    private static boolean rebalance = true;

    // Thread Monitoring
    private static HashMap<IThreaded, Long> timeMap = new HashMap<>();
    private static HashSet<IThreaded> allToExecute = new HashSet<>();
    private static ArrayList<HashSet<IThreaded>> threadExecuting = new ArrayList<>();
    private static int totalThreads;

    // Thread execution management
    private static HashSet<IThreaded> toBeAdded = new HashSet<>();
    private static HashSet<IThreaded> toBeRemoved = new HashSet<>();
    private static int iterator = 0;
    private static boolean clearAddedAndRemoved = false;

    static void init() {
        mainThread = Thread.currentThread();
    }

    private static boolean done() {
        int threadsDone = 0;

        for (MCThread thread : fullThreads)
            if (thread.getDone())
                threadsDone++;

        for (SubThread thread : threadsToExecute)
            if (thread.getDone())
                threadsDone++;

        return threadsDone == threadsToExecute.size() + fullThreads.size();
    }

    static void update() {
        ticks = 0;
        if (multithreded) {
            if (!done()) {
                mainWaiting = true;
                while (true) {
                    try {
                        if (ticks >= 500) {
                            tickToLong();
                        }
                        if (done()) {
                            break;
                        }
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            allThreadsDone = false;
            threadsToExecute.forEach(SubThread::update);
        } else
            allToExecute.forEach(IThreaded::update);
        fullThreads.forEach(MCThread::update);
        checkThreads();
        LTT = System.nanoTime() - TT;
        TT = System.nanoTime();
    }

    private static void tickToLong() {
        int threadsDone = 0;

        for (SubThread thread : threadsToExecute)
            if (thread.getDone())
                threadsDone++;

        if (threadsDone == threadsToExecute.size()) {
            // remove stuck thread and log error
            fullThreads.stream().filter(thread -> !thread.getDone()).forEach(thread -> {
                unregister(thread);
                FMLLog.severe("Thread '" + thread.getName() + "' took to long to tick, removed from sync registry");
            });
        } else {
            // IThreaded stuck, this should not happen but just in case ill remove it, and restart a new thread next tick.
            HashSet<SubThread> stuckThreads = threadsToExecute.stream().filter(thread -> !thread.getDone()).collect(Collectors.toCollection(HashSet::new));
            for (SubThread thread : stuckThreads) {
                allToExecute.remove(thread.getCurrentIThreaded());
                thread.removeCurrentIThreaded();
            }
        }
    }

    static void removeThread(HashSet<IThreaded> iThreadedHashSet, SubThread thread) {
        threadExecuting.remove(iThreadedHashSet);
        threadsToExecute.remove(thread);
        forceRebalance();
    }

    private static void forceRebalance() {
        rebalance = true;
    }

    private static void checkThreads() {
        if (rebalance || totalThreads != Runtime.getRuntime().availableProcessors() || checkTimes() > 0)
            rebalanceThreads();
        else
            addAndRemove();
        fullThreads.stream().filter(thread -> !thread.isAlive()).forEach(thread -> fullThreads.remove(thread));
    }

    private static int checkTimes() {
        int threadsOverTime = 0;
        for (SubThread thread : threadsToExecute) {
            if ((thread.getLastTickTime() / 1_000_000) >= 50)
                threadsOverTime++;
        }
        return threadsOverTime;
    }

    private static void rebalanceThreads() {


        if (!rebalance && totalThreads == Runtime.getRuntime().availableProcessors() && totalThreads == checkTimes())
            // CPU is maxed, get a better computer.
            return;

        rebalance = false;

        // Somebody changed the number of cores i can use......
        threadExecuting.clear();
        if (totalThreads != Runtime.getRuntime().availableProcessors() || threadExecuting.size() == 0) {
            threadsToExecute.forEach(SubThread::kill);
            threadsToExecute.clear();

            totalThreads = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < totalThreads; i++) {
                threadExecuting.add(new HashSet<>());
                threadsToExecute.add(new SubThread(threadExecuting.get(i)).begin());
            }
        }

        ArrayList<HashSet<IThreaded>> threads = new ArrayList<>();
        threads.addAll(threadExecuting);
        for (int i = totalThreads - 1; i >= 0; i--)
            threads.add(threadExecuting.get(i));

        while (true)
            try {
                if (done()) {
                    break;
                }
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
            }

        int i = 0;
        for (IThreaded iThreaded : allToExecute) {
            threads.get(i).add(iThreaded);
            if (++i >= threads.size())
                i = 0;
        }
    }

    private static void addAndRemove() {
        toBeRemoved.forEach(iThreaded -> {
            toBeAdded.remove(iThreaded);
            allToExecute.remove(iThreaded);
        });

        for (IThreaded add : toBeAdded) {
            if (++iterator >= totalThreads)
                iterator = 0;
            threadExecuting.get(iterator).add(add);
            allToExecute.add(add);
        }

        for (IThreaded add : toBeRemoved) {
            for (HashSet set : threadExecuting)
                set.remove(toBeRemoved);
            allToExecute.remove(add);
        }

        clearAddedAndRemoved = true;

    }

    static void setTickTime(IThreaded iThreaded, long time) {
        timeMap.put(iThreaded, time);
    }

    public static void register(IThreaded toRegister) {
        if (clearAddedAndRemoved) {
            toBeAdded.clear();
            toBeRemoved.clear();
        }
        if (allToExecute.contains(toRegister))
            return;
        toBeAdded.add(toRegister);
    }

    public static void unregister(IThreaded toUnregister) {
        if (clearAddedAndRemoved) {
            toBeAdded.clear();
            toBeRemoved.clear();
        }
        if (!allToExecute.contains(toUnregister))
            return;
        toBeRemoved.add(toUnregister);
    }

    public static void register(MCThread toRegister) {
        fullThreads.add(toRegister);
    }

    public static void unregister(MCThread toUnregister) {
        fullThreads.remove(toUnregister);
    }

    static void worldUnload() {
        threadExecuting.forEach(HashSet::clear);
        allToExecute.clear();
        toBeAdded.clear();
        toBeRemoved.clear();
        timeMap.clear();
    }
}
