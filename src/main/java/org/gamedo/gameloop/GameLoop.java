package org.gamedo.gameloop;

import lombok.Synchronized;
import lombok.experimental.Delegate;
import lombok.extern.log4j.Log4j2;
import org.gamedo.concurrent.NamedThreadFactory;
import org.gamedo.ecs.Entity;
import org.gamedo.ecs.interfaces.IEntity;
import org.gamedo.ecs.interfaces.IGameLoopEntityManager;
import org.gamedo.gameloop.interfaces.GameLoopFunction;
import org.gamedo.gameloop.interfaces.IGameLoop;

import java.util.Optional;
import java.util.concurrent.*;

@Log4j2
public class GameLoop extends Entity implements IGameLoop {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<IGameLoop> gameLoopOptional = Optional.of(this);
    @Delegate(types = ScheduledExecutorService.class)
    private final ScheduledExecutorService scheduledExecutorService;

    private ScheduledFuture<?> future;
    private long lastTickMilliSecond;
    private long lastTickInterval;
    private volatile Thread thread;

    public GameLoop(final String id) {

        super(id);

        scheduledExecutorService = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory(id)) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                super.beforeExecute(t, r);

                thread = Thread.currentThread();
                IGameLoop.GAME_LOOP_THREAD_LOCAL.set(gameLoopOptional);
            }

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);

                IGameLoop.GAME_LOOP_THREAD_LOCAL.set(Optional.empty());
                thread = null;
            }
        };
    }

    @Override
    public boolean inGameLoop() {
        return thread == Thread.currentThread();
    }

    @Override
    @Synchronized
    public boolean run(long initialDelay, long period, TimeUnit periodTimeUnit) {

        if (future != null) {
            return false;
        }

        lastTickMilliSecond = System.currentTimeMillis();

        final Runnable runnable = () -> {
                final long currentTimeMillis = System.currentTimeMillis();

                lastTickInterval = currentTimeMillis - lastTickMilliSecond;
                lastTickMilliSecond = currentTimeMillis;

                tick(lastTickInterval);
        };

        future = scheduledExecutorService.scheduleAtFixedRate(runnable, initialDelay, period, periodTimeUnit);
        return true;
    }

    @Override
    public <R> CompletableFuture<R> submit(GameLoopFunction<R> function) {

        if (inGameLoop()) {
            return CompletableFuture.completedFuture(function.apply(this));
        } else {
            return CompletableFuture.supplyAsync(() -> function.apply(this), this);
        }
    }

    @Override
    public void tick(long elapse) {
        final Optional<IGameLoopEntityManager> registerOptional = getComponent(IGameLoopEntityManager.class);
        //这里要是用副本，否则在tick期间可能会出现修改map的情况，当然有这里还有优化空间
        registerOptional.ifPresent(register -> register.getEntityMap().forEach((entityId, entity) -> safeTick(entity, elapse)));
    }

    private static void safeTick(final IEntity entity, long elapse) {
        try {
            entity.tick(elapse);
        } catch (Throwable e) {
            log.error("exception caught, entity:" + entity, e);
        }
    }
}
