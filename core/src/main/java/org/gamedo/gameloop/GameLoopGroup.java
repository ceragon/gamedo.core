package org.gamedo.gameloop;

import lombok.extern.slf4j.Slf4j;
import org.gamedo.gameloop.interfaces.GameLoopFunction;
import org.gamedo.gameloop.interfaces.IGameLoop;
import org.gamedo.gameloop.interfaces.IGameLoopGroup;
import org.gamedo.utils.Pair;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class GameLoopGroup implements IGameLoopGroup {
    private final String id;
    private final AtomicInteger idx = new AtomicInteger(0);
    private final IGameLoop[] gameLoops;

    public GameLoopGroup(String id, IGameLoop... gameLoops) {
        this.id = id;
        this.gameLoops = gameLoops.clone();
    }

    public GameLoopGroup(String id, int gameLoopCount) {
        this.id = id;
        gameLoops = IntStream.rangeClosed(1, gameLoopCount)
                .mapToObj(value -> new GameLoop(id + '-' + value))
                .toArray(GameLoop[]::new);
    }

    public GameLoopGroup(String id) {
        this(id, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public void shutdown() {
        Arrays.stream(gameLoops).forEach(gameLoop -> gameLoop.shutdown());
    }

    @Override
    public List<Runnable> shutdownNow() {

        return Arrays.stream(gameLoops)
                .map(gameLoop -> gameLoop.shutdownNow())
                .flatMap(runnables -> runnables.stream())
                .collect(Collectors.toList());
    }

    @Override
    public boolean isShutdown() {
        return Arrays.stream(gameLoops).allMatch(IGameLoop::isShutdown);
    }

    @Override
    public boolean isTerminated() {
        return Arrays.stream(gameLoops).allMatch(IGameLoop::isTerminated);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {

        final boolean allTerminated = Arrays.stream(gameLoops)
                .parallel()
                .allMatch(iGameLoop -> {
                    try {
                        return iGameLoop.awaitTermination(timeout, unit);
                    } catch (InterruptedException e) {
                        return false;
                    }
                });

        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        return allTerminated;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return selectNext().submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return selectNext().submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return selectNext().submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return selectNext().invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return selectNext().invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return selectNext().invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return selectNext().invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        selectNext().execute(command);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public IGameLoop[] selectAll() {
        return gameLoops.clone();
    }

    @Override
    public IGameLoop selectNext() {
        return gameLoops[Math.abs(idx.getAndIncrement() % gameLoops.length)];
    }

    @Override
    public <C extends Comparable<? super C>> List<IGameLoop> select(GameLoopFunction<C> chooser,
                                                                    Comparator<C> comparator,
                                                                    int limit) {
        return Arrays.stream(gameLoops)
                .parallel()
                .map(iGameLoop -> {
                    try {

                        //这里用join可能有风险，万一逻辑代码有问题的话，会阻塞当前线程！不过没想到更好的办法
                        return Pair.of(iGameLoop.submit(chooser).join(), iGameLoop);
                    } catch (Throwable t) {
                        log.error("exception caught.", t);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Pair::getK, comparator))
                .limit(limit)
                .map(Pair::getV)
                .collect(Collectors.toList());
    }

    @Override
    public List<IGameLoop> select(GameLoopFunction<Boolean> filter) {

        return Arrays.stream(gameLoops)
                .filter(iGameLoop -> {
                    try {
                        //这里用join可能有风险，万一逻辑代码有问题的话，会阻塞当前线程！不过没想到更好的办法
                        return iGameLoop.submit(filter).join();
                    } catch (Throwable t) {
                        log.error("exception caught.", t);
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }
}
