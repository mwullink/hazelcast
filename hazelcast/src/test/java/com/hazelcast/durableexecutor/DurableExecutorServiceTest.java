/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.durableexecutor;

import com.hazelcast.config.Config;
import com.hazelcast.config.DurableExecutorConfig;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.core.IAtomicLong;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.ManagedContext;
import com.hazelcast.core.Member;
import com.hazelcast.core.PartitionAware;
import com.hazelcast.executor.ExecutorServiceTestSupport;
import com.hazelcast.spi.properties.GroupProperty;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class DurableExecutorServiceTest extends ExecutorServiceTestSupport {

    public static final int NODE_COUNT = 3;

    public static final int TASK_COUNT = 1000;

    @Test
    public void test_registerCallback_beforeFutureIsCompletedOnOtherNode() throws ExecutionException, InterruptedException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();

        assertTrue(instance1.getCountDownLatch("latch").trySetCount(1));

        String name = randomString();
        DurableExecutorService executorService = instance2.getDurableExecutorService(name);
        ICountDownLatchAwaitCallable task = new ICountDownLatchAwaitCallable("latch");
        String key = generateKeyOwnedBy(instance1);
        ICompletableFuture<Boolean> future = executorService.submitToKeyOwner(task, key);
        final CountingDownExecutionCallback<Boolean> callback = new CountingDownExecutionCallback<Boolean>(1);
        future.andThen(callback);
        instance1.getCountDownLatch("latch").countDown();
        assertTrue(future.get());
        assertOpenEventually(callback.getLatch());
    }

    @Test
    public void test_registerCallback_afterFutureIsCompletedOnOtherNode() throws ExecutionException, InterruptedException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();
        String name = randomString();
        DurableExecutorService executorService = instance2.getDurableExecutorService(name);
        BasicTestCallable task = new BasicTestCallable();
        String key = generateKeyOwnedBy(instance1);
        ICompletableFuture<String> future = executorService.submitToKeyOwner(task, key);
        assertEquals(BasicTestCallable.RESULT, future.get());
        ;

        final CountingDownExecutionCallback<String> callback = new CountingDownExecutionCallback<String>(1);
        future.andThen(callback);

        assertOpenEventually(callback.getLatch(), 10);
    }

    @Test
    public void test_registerCallback_multipleTimes_futureIsCompletedOnOtherNode() throws ExecutionException, InterruptedException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();

        assertTrue(instance1.getCountDownLatch("latch").trySetCount(1));

        String name = randomString();
        DurableExecutorService executorService = instance2.getDurableExecutorService(name);
        ICountDownLatchAwaitCallable task = new ICountDownLatchAwaitCallable("latch");
        String key = generateKeyOwnedBy(instance1);
        ICompletableFuture<Boolean> future = executorService.submitToKeyOwner(task, key);
        final CountDownLatch latch = new CountDownLatch(2);
        final CountingDownExecutionCallback<Boolean> callback = new CountingDownExecutionCallback<Boolean>(latch);
        future.andThen(callback);
        future.andThen(callback);
        instance1.getCountDownLatch("latch").countDown();
        assertTrue(future.get());
        assertOpenEventually(latch, 10);
    }

    @Test
    public void testSubmitFailingCallableException_withExecutionCallback() throws ExecutionException, InterruptedException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(1);
        HazelcastInstance instance = factory.newHazelcastInstance();
        DurableExecutorService service = instance.getDurableExecutorService(randomString());
        final CountingDownExecutionCallback callback = new CountingDownExecutionCallback(1);
        service.submit(new FailingTestTask()).andThen(callback);
        assertOpenEventually(callback.getLatch());
        assertTrue(callback.getResult() instanceof Throwable);
    }

    /* ############ submit runnable ############ */

    @Test
    public void testManagedContextAndLocal() throws Exception {
        final Config config = new Config();
        config.addDurableExecutorConfig(new DurableExecutorConfig("test").setPoolSize(1));
        final AtomicBoolean initialized = new AtomicBoolean();
        config.setManagedContext(new ManagedContext() {
            @Override
            public Object initialize(Object obj) {
                if (obj instanceof RunnableWithManagedContext) {
                    initialized.set(true);
                }
                return obj;
            }
        });

        HazelcastInstance instance = createHazelcastInstance(config);
        DurableExecutorService executor = instance.getDurableExecutorService("test");

        RunnableWithManagedContext task = new RunnableWithManagedContext();
        executor.submit(task).get();
        assertTrue("The task should have been initialized by the ManagedContext", initialized.get());
    }

    static class RunnableWithManagedContext implements Runnable, Serializable {

        @Override
        public void run() {
        }
    }

    @Test
    public void hazelcastInstanceAwareAndLocal() throws Exception {
        final Config config = new Config();
        config.addDurableExecutorConfig(new DurableExecutorConfig("test").setPoolSize(1));
        final HazelcastInstance instance = createHazelcastInstance(config);
        DurableExecutorService executor = instance.getDurableExecutorService("test");

        HazelcastInstanceAwareRunnable task = new HazelcastInstanceAwareRunnable();
        // if 'setHazelcastInstance' not called we expect a RuntimeException
        executor.submit(task).get();
    }

    static class HazelcastInstanceAwareRunnable implements Runnable, HazelcastInstanceAware, Serializable {
        private transient boolean initializeCalled = false;

        @Override
        public void run() {
            if (!initializeCalled) {
                throw new RuntimeException("The setHazelcastInstance should have been called");
            }
        }

        @Override
        public void setHazelcastInstance(HazelcastInstance hazelcastInstance) {
            initializeCalled = true;
        }
    }

    @Test
    public void testExecuteMultipleNode() throws InterruptedException, ExecutionException, TimeoutException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(NODE_COUNT);
        HazelcastInstance[] instances = factory.newInstances();
        for (int i = 0; i < NODE_COUNT; i++) {
            DurableExecutorService service = instances[i].getDurableExecutorService("testExecuteMultipleNode");
            int rand = new Random().nextInt(100);
            Future<Integer> future = service.submit(new IncrementAtomicLongRunnable("count"), rand);
            assertEquals(Integer.valueOf(rand), future.get(10, TimeUnit.SECONDS));
        }

        IAtomicLong count = instances[0].getAtomicLong("count");
        assertEquals(NODE_COUNT, count.get());
    }

    @Test
    public void testSubmitToKeyOwnerRunnable() throws InterruptedException {
        final int k = NODE_COUNT;
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(k);
        HazelcastInstance[] instances = factory.newInstances();
        final AtomicInteger nullResponseCount = new AtomicInteger(0);
        final CountDownLatch responseLatch = new CountDownLatch(k);
        final ExecutionCallback callback = new ExecutionCallback() {
            public void onResponse(Object response) {
                if (response == null) {
                    nullResponseCount.incrementAndGet();
                }
                responseLatch.countDown();
            }

            public void onFailure(Throwable t) {
            }
        };
        for (int i = 0; i < k; i++) {
            HazelcastInstance instance = instances[i];
            DurableExecutorService service = instance.getDurableExecutorService("testSubmitToKeyOwnerRunnable");
            Member localMember = instance.getCluster().getLocalMember();
            int key = findNextKeyForMember(instance, localMember);
            service.submitToKeyOwner(
                    new IncrementAtomicLongIfMemberUUIDNotMatchRunnable(localMember.getUuid(), "testSubmitToKeyOwnerRunnable"),
                    key).andThen(callback);
        }
        assertOpenEventually(responseLatch);
        assertEquals(0, instances[0].getAtomicLong("testSubmitToKeyOwnerRunnable").get());
        assertEquals(k, nullResponseCount.get());
    }

    /* ############ submit callable ############ */

    /**
     * Submit a null task must raise a NullPointerException
     */
    @Test(expected = NullPointerException.class)
    public void submitNullTask() throws Exception {
        DurableExecutorService executor = createSingleNodeDurableExecutorService("submitNullTask");
        Callable c = null;
        executor.submit(c);
    }

    /**
     * Run a basic task
     */
    @Test
    public void testBasicTask() throws Exception {
        Callable<String> task = new BasicTestCallable();
        DurableExecutorService executor = createSingleNodeDurableExecutorService("testBasicTask");
        Future future = executor.submit(task);
        assertEquals(future.get(), BasicTestCallable.RESULT);
    }

    @Test
    public void testSubmitMultipleNode() throws ExecutionException, InterruptedException {
        final int k = NODE_COUNT;
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(k);
        final HazelcastInstance[] instances = factory.newInstances();
        for (int i = 0; i < k; i++) {
            DurableExecutorService service = instances[i].getDurableExecutorService("testSubmitMultipleNode");
            Future future = service.submit(new IncrementAtomicLongCallable("testSubmitMultipleNode"));
            assertEquals((long) (i + 1), future.get());
        }
    }

    @Test
    public void testSubmitToKeyOwnerCallable() throws Exception {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(NODE_COUNT);
        HazelcastInstance[] instances = factory.newInstances();

        final List<Future> futures = new ArrayList<Future>();

        for (int i = 0; i < NODE_COUNT; i++) {
            HazelcastInstance instance = instances[i];
            DurableExecutorService service = instance.getDurableExecutorService("testSubmitToKeyOwnerCallable");

            Member localMember = instance.getCluster().getLocalMember();
            int key = findNextKeyForMember(instance, localMember);
            Future f = service.submitToKeyOwner(new MemberUUIDCheckCallable(localMember.getUuid()), key);
            futures.add(f);
        }

        for (Future f : futures) {
            assertTrue((Boolean) f.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSubmitToKeyOwnerCallable_withCallback() throws Exception {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(NODE_COUNT);
        HazelcastInstance[] instances = factory.newInstances();
        BooleanSuccessResponseCountingCallback callback = new BooleanSuccessResponseCountingCallback(NODE_COUNT);

        for (int i = 0; i < NODE_COUNT; i++) {
            HazelcastInstance instance = instances[i];
            DurableExecutorService service = instance.getDurableExecutorService("testSubmitToKeyOwnerCallable");
            Member localMember = instance.getCluster().getLocalMember();
            int key = findNextKeyForMember(instance, localMember);
            service.submitToKeyOwner(new MemberUUIDCheckCallable(localMember.getUuid()), key).andThen(callback);
        }

        assertOpenEventually(callback.getResponseLatch());
        assertEquals(NODE_COUNT, callback.getSuccessResponseCount());
    }

    /* ############ future ############ */

    /**
     * Test the method isDone()
     */
    @Test
    public void testIsDoneMethod() throws Exception {
        Callable<String> task = new BasicTestCallable();
        DurableExecutorService executor = createSingleNodeDurableExecutorService("isDoneMethod");
        Future future = executor.submit(task);
        assertResult(future, BasicTestCallable.RESULT);
    }

    /**
     * Test for the issue 129.
     * Repeatedly runs tasks and check for isDone() status after
     * get().
     */
    @Test
    public void testIsDoneMethod2() throws Exception {
        DurableExecutorService executor = createSingleNodeDurableExecutorService("isDoneMethod2");
        for (int i = 0; i < TASK_COUNT; i++) {
            Callable<String> task1 = new BasicTestCallable();
            Callable<String> task2 = new BasicTestCallable();
            Future future1 = executor.submit(task1);
            Future future2 = executor.submit(task2);
            assertResult(future2, BasicTestCallable.RESULT);
            assertResult(future1, BasicTestCallable.RESULT);
        }
    }

    /**
     * Test multiple Future.get() invocation
     */
    @Test
    public void testMultipleFutureGets() throws Exception {
        Callable<String> task = new BasicTestCallable();
        DurableExecutorService executor = createSingleNodeDurableExecutorService("isTwoGetFromFuture");
        Future<String> future = executor.submit(task);
        assertResult(future, BasicTestCallable.RESULT);
        assertResult(future, BasicTestCallable.RESULT);
        assertResult(future, BasicTestCallable.RESULT);
        assertResult(future, BasicTestCallable.RESULT);
    }

    private void assertResult(Future future, Object expected) throws Exception {
        assertEquals(future.get(), expected);
        assertTrue(future.isDone());
    }

    @Test
    public void testIssue292() throws Exception {
        final CountingDownExecutionCallback<Member> callback = new CountingDownExecutionCallback<Member>(1);
        createSingleNodeDurableExecutorService("testIssue292").submit(new MemberCheck()).andThen(callback);
        assertOpenEventually(callback.getLatch());
        assertTrue(callback.getResult() instanceof Member);
    }

    /**
     * Execute a task that is executing
     * something else inside. Nested Execution.
     */
    @Test
    public void testNestedExecution() throws Exception {
        Callable<String> task = new NestedExecutorTask();
        DurableExecutorService executor = createSingleNodeDurableExecutorService("testNestedExecution");
        Future future = executor.submit(task);
        assertCompletesEventually(future);
    }

    /**
     * Shutdown-related method behaviour when the cluster is running
     */
    @Test
    public void testShutdownBehaviour() throws Exception {
        DurableExecutorService executor = createSingleNodeDurableExecutorService("testShutdownBehaviour");
        // Fresh instance, is not shutting down
        assertFalse(executor.isShutdown());
        assertFalse(executor.isTerminated());
        executor.shutdown();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        // shutdownNow() should return an empty list and be ignored
        List<Runnable> pending = executor.shutdownNow();
        assertTrue(pending.isEmpty());
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        // awaitTermination() should return immediately false
        try {
            boolean terminated = executor.awaitTermination(60L, TimeUnit.SECONDS);
            assertFalse(terminated);
        } catch (InterruptedException ie) {
            fail("InterruptedException");
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
    }

    /**
     * Shutting down the cluster should act as the ExecutorService shutdown
     */
    @Test(expected = RejectedExecutionException.class)
    public void testClusterShutdown() throws Exception {
        ExecutorService executor = createSingleNodeDurableExecutorService("testClusterShutdown");
        shutdownNodeFactory();
        Thread.sleep(2000);

        assertNotNull(executor);
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());

        // New tasks must be rejected
        Callable<String> task = new BasicTestCallable();
        executor.submit(task);
    }

    @Test
    public void testStatsIssue2039() throws InterruptedException, ExecutionException, TimeoutException {
        Config config = new Config();
        String name = "testStatsIssue2039";
        config.addDurableExecutorConfig(new DurableExecutorConfig(name).setPoolSize(1).setCapacity(1));
        HazelcastInstance instance = createHazelcastInstance(config);
        DurableExecutorService executorService = instance.getDurableExecutorService(name);


        executorService.execute(new SleepLatchRunnable());

        assertOpenEventually(SleepLatchRunnable.startLatch, 30);
        Future waitingInQueue = executorService.submit(new EmptyRunnable());

        Future rejected = executorService.submit(new EmptyRunnable());

        try {
            rejected.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            boolean isRejected = e.getCause() instanceof RejectedExecutionException;
            if (!isRejected) {
                fail(e.toString());
            }
        } finally {
            SleepLatchRunnable.sleepLatch.countDown();
        }

        waitingInQueue.get(1, TimeUnit.MINUTES);

//        final LocalExecutorStats stats = executorService.getLocalExecutorStats();
//        assertEquals(2, stats.getStartedTaskCount());
//        assertEquals(0, stats.getPendingTaskCount());
        //todo ali
    }

    @Test
    public void testExecutorServiceStats() throws InterruptedException, ExecutionException {
        DurableExecutorService executorService = createSingleNodeDurableExecutorService("testExecutorServiceStats");
        final int k = 10;
        LatchRunnable.latch = new CountDownLatch(k);

        for (int i = 0; i < k; i++) {
            executorService.execute(new LatchRunnable());
        }
        assertOpenEventually(LatchRunnable.latch);

        final Future<Boolean> f = executorService.submit(new SleepingTask(10));
        Thread.sleep(1000);
        f.cancel(true);
        try {
            f.get();
        } catch (CancellationException e) {
        }

//        final LocalExecutorStats stats = executorService.getLocalExecutorStats();
//        assertEquals(k + 1, stats.getStartedTaskCount());
//        assertEquals(k, stats.getCompletedTaskCount());
//        assertEquals(0, stats.getPendingTaskCount());
//        assertEquals(1, stats.getCancelledTaskCount());
        //todo ali
    }

    static class LatchRunnable implements Runnable, Serializable {

        static CountDownLatch latch;
        final int executionTime = 200;

        @Override
        public void run() {
            try {
                Thread.sleep(executionTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            latch.countDown();
        }
    }

    @Test
    public void testLongRunningCallable() throws ExecutionException, InterruptedException, TimeoutException {
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);

        Config config = new Config();
        long callTimeoutMillis = 3000;
        config.setProperty(GroupProperty.OPERATION_CALL_TIMEOUT_MILLIS.getName(), String.valueOf(callTimeoutMillis));

        HazelcastInstance hz1 = factory.newHazelcastInstance(config);
        HazelcastInstance hz2 = factory.newHazelcastInstance(config);
        String key = generateKeyOwnedBy(hz2);

        DurableExecutorService executor = hz1.getDurableExecutorService("test");
        Future<Boolean> f = executor.submitToKeyOwner(new SleepingTask(MILLISECONDS.toSeconds(callTimeoutMillis) * 3),key);

        Boolean result = f.get(1, TimeUnit.MINUTES);
        assertTrue(result);
    }

    static class ICountDownLatchAwaitCallable implements Callable<Boolean>, HazelcastInstanceAware, Serializable {

        private final String name;

        private HazelcastInstance instance;

        public ICountDownLatchAwaitCallable(String name) {
            this.name = name;
        }

        @Override
        public Boolean call()
                throws Exception {
            return instance.getCountDownLatch(name).await(100, TimeUnit.SECONDS);
        }

        @Override
        public void setHazelcastInstance(HazelcastInstance instance) {
            this.instance = instance;
        }
    }

    static class SleepLatchRunnable implements Runnable, Serializable {

        static CountDownLatch startLatch;
        static CountDownLatch sleepLatch;

        public SleepLatchRunnable() {
            startLatch = new CountDownLatch(1);
            sleepLatch = new CountDownLatch(1);
        }

        @Override
        public void run() {
            startLatch.countDown();
            assertOpenEventually(sleepLatch);
        }
    }

    static class EmptyRunnable implements Runnable, Serializable, PartitionAware {
        @Override
        public void run() {
        }

        @Override
        public Object getPartitionKey() {
            return "key";
        }
    }
}
