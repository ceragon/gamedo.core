package org.gamedo.gameloop.interfaces;

import lombok.extern.slf4j.Slf4j;
import org.gamedo.annotation.Tick;
import org.gamedo.configuration.GamedoConfiguration;
import org.gamedo.ecs.Entity;
import org.gamedo.gameloop.components.entitymanager.interfaces.IGameLoopEntityManager;
import org.gamedo.gameloop.functions.IGameLoopEntityManagerFunction;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest(classes = GamedoConfiguration.class)
class IGameLoopGroupTest {

    private IGameLoopGroup gameLoopGroup;
    private final ConfigurableApplicationContext context;

    IGameLoopGroupTest(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @BeforeEach
    void setUp() {
        gameLoopGroup = context.getBean(IGameLoopGroup.class);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gameLoopGroup.shutdown();
        final boolean b = gameLoopGroup.awaitTermination(10, TimeUnit.SECONDS);
        Assertions.assertTrue(b);
    }

    @Test
    @Order(1)
    void testSelectNext() {
        IGameLoop iGameLoop1 = gameLoopGroup.selectNext();
        Assertions.assertEquals(gameLoopGroup.getId() + "-1", iGameLoop1.getId());

        for (int i = 0; i < 1000000; i++) {
            final IGameLoop iGameLoop2 = gameLoopGroup.selectNext();
            Assertions.assertNotSame(iGameLoop2, iGameLoop1);
            iGameLoop1 = iGameLoop2;
        }
    }

    @Test
    void testSelectChooser() {
        final IGameLoop iGameLoop1 = gameLoopGroup.selectNext();
        final String id = UUID.randomUUID().toString();
        final CompletableFuture<Boolean> future = iGameLoop1.submit(IGameLoopEntityManagerFunction.registerEntity(new Entity(id)));
        future.join();

        //选择实体数量最多的一个（实际业务中，是选取实体数量最少的一个，这里是为了方便测试）
        final List<IGameLoop> gameLoopList1 = gameLoopGroup.select(IGameLoopEntityManagerFunction.getEntityCount(), Comparator.reverseOrder(), 1);
        Assertions.assertEquals(1, gameLoopList1.size());
        Assertions.assertSame(iGameLoop1, gameLoopList1.get(0));

        //选择实体所在的那个IGameLoop
        final List<IGameLoop> gameLoopList2 = gameLoopGroup.select(IGameLoopEntityManagerFunction.hasEntity(id), Comparator.reverseOrder(), 1);
        Assertions.assertEquals(1, gameLoopList2.size());
        Assertions.assertSame(iGameLoop1, gameLoopList2.get(0));
    }

    @Test
    void testSelectFilter() {

        final HashSet<String> entityIdSet = new HashSet<>(Arrays.asList("a", "b", "c"));
        final List<Boolean> collect = entityIdSet.stream()
                .parallel()
                .map(s -> gameLoopGroup.selectNext().submit(IGameLoopEntityManagerFunction.registerEntity(new Entity(s))).join())
                .distinct()
                .collect(Collectors.toList());

        Assertions.assertEquals(1, collect.size());
        Assertions.assertEquals(true, collect.get(0));

        final List<IGameLoop> gameLoopList = gameLoopGroup.select(gameLoop -> gameLoop.getComponent(IGameLoopEntityManager.class)
                .map(iGameLoopEntityManager -> iGameLoopEntityManager.getEntityMap().keySet()
                        .stream()
                        .anyMatch(s -> entityIdSet.contains(s)))
                .orElse(false)
        );

        final Object[] expected = Arrays.stream(gameLoopGroup.selectAll()).limit(3).toArray();
        final Object[] actual = gameLoopList.toArray();
        Assertions.assertArrayEquals(expected, actual);
    }

    @Test
    void testBenchmark() {

        final int entityCountBase = 10000;
        final int gameLoopCount = gameLoopGroup.selectAll().length;
        final int mod = entityCountBase % gameLoopCount;
        final int entityCount = Math.max(gameLoopCount, entityCountBase - mod);

        log.info("gameLoop count:{}", gameLoopCount);
        log.info("entity count:{}", entityCount);
        final List<CompletableFuture<Boolean>> submitFutureList = new ArrayList<>(entityCount);
        final Map<String, CompletableFuture<Boolean>> futureMap = new ConcurrentHashMap<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            final IGameLoop iGameLoop = gameLoopGroup.selectNext();
            final String id = UUID.randomUUID().toString();
            futureMap.put(id, new CompletableFuture<>());
            final Entity entity = new MyEntity(id, futureMap);

            final CompletableFuture<Boolean> submit = iGameLoop.submit(IGameLoopEntityManagerFunction.registerEntity(entity));
            submitFutureList.add(submit);
        }

        log.info("begin parallel join");
        final long submitFailedCount = submitFutureList.stream()
                .parallel()
                .map(CompletableFuture::join)
                .filter(b -> !b.booleanValue())
                .count();
        log.info("finish parallel join");

        Assertions.assertEquals(0, submitFailedCount);

        final long tickFailedcount = futureMap.values().stream()
                .parallel()
                .map(f -> {
                    try {
                        return f.get(100, TimeUnit.MILLISECONDS);
                    } catch (Throwable ignored) {
                    }
                    return false;
                })
                .filter(b -> !b.booleanValue())
                .count();

        log.info("finish tick");
        Assertions.assertEquals(0, tickFailedcount);

        gameLoopGroup.shutdown();
        Assertions.assertDoesNotThrow(() -> gameLoopGroup.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static class MyEntity extends Entity {
        private final Map<String, CompletableFuture<Boolean>> futureMap;

        private MyEntity(String id, Map<String, CompletableFuture<Boolean>> futureMap) {
            super(id);
            this.futureMap = futureMap;
        }

        @Tick(tick = 10)
        public void myTick(Long currentMilliSecond, Long lastMilliSecond) {
            futureMap.get(getId()).complete(true);
        }
    }
}
