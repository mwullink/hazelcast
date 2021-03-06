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
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.executor.ExecutorServiceTestSupport;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class DurableRetrieveResultTest extends ExecutorServiceTestSupport {

    @Test
    public void testRetrieveAndDispose_WhenSubmitterMemberDown() throws ExecutionException, InterruptedException {
        String name = randomString();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();
        String key = generateKeyOwnedBy(instance2);

        DurableExecutorService executorService = instance1.getDurableExecutorService(name);
        SleepingTask task = new SleepingTask(4);
        long taskId = executorService.submitToKeyOwner(task, key).getTaskId();

        instance1.shutdown();

        executorService = instance2.getDurableExecutorService(name);
        Future<Boolean> future = executorService.retrieveAndDisposeResult(taskId);
        assertTrue(future.get());

        Future<Object> f = executorService.retrieveResult(taskId);
        assertNull(f.get());
    }

    @Test
    public void testRetrieveAndDispose_WhenOwnerMemberDown() throws ExecutionException, InterruptedException {
        String name = randomString();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();
        String key = generateKeyOwnedBy(instance1);

        DurableExecutorService executorService = instance1.getDurableExecutorService(name);
        SleepingTask task = new SleepingTask(4);
        long taskId = executorService.submitToKeyOwner(task, key).getTaskId();

        instance1.shutdown();

        executorService = instance2.getDurableExecutorService(name);
        Future<Boolean> future = executorService.retrieveAndDisposeResult(taskId);
        assertTrue(future.get());

        Future<Object> f = executorService.retrieveResult(taskId);
        assertNull(f.get());
    }

    @Test
    public void testRetrieve_WhenSubmitterMemberDown() throws ExecutionException, InterruptedException {
        String name = randomString();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();
        String key = generateKeyOwnedBy(instance2);

        DurableExecutorService executorService = instance1.getDurableExecutorService(name);
        SleepingTask task = new SleepingTask(4);
        long taskId = executorService.submitToKeyOwner(task, key).getTaskId();

        instance1.shutdown();

        executorService = instance2.getDurableExecutorService(name);
        Future<Boolean> future = executorService.retrieveResult(taskId);
        assertTrue(future.get());
    }

    @Test
    public void testRetrieve_WhenOwnerMemberDown() throws ExecutionException, InterruptedException {
        String name = randomString();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(2);
        HazelcastInstance instance1 = factory.newHazelcastInstance();
        HazelcastInstance instance2 = factory.newHazelcastInstance();
        String key = generateKeyOwnedBy(instance1);

        DurableExecutorService executorService = instance1.getDurableExecutorService(name);
        SleepingTask task = new SleepingTask(4);
        long taskId = executorService.submitToKeyOwner(task, key).getTaskId();

        instance1.shutdown();

        executorService = instance2.getDurableExecutorService(name);
        Future<Boolean> future = executorService.retrieveResult(taskId);
        assertTrue(future.get());
    }

    @Test
    public void testRetrieve_WhenResultOverwritten() throws ExecutionException, InterruptedException {
        String name = randomString();
        Config config = new Config();
        config.getDurableExecutorConfig(name).setCapacity(1);
        HazelcastInstance instance = createHazelcastInstance(config);
        DurableExecutorService executorService = instance.getDurableExecutorService(name);
        DurableExecutorServiceFuture<String> future = executorService.submitToKeyOwner(new BasicTestCallable(), name);
        long taskId = future.getTaskId();
        future.get();

        executorService.submitToKeyOwner(new BasicTestCallable(), name);

        Future<Object> f = executorService.retrieveResult(taskId);
        try {
            f.get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof StaleTaskIdException);
        }
    }

}
