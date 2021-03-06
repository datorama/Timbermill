package com.datorama.oss.timbermill.common.persistence;

import com.datorama.oss.timbermill.common.redis.RedisService;
import com.datorama.oss.timbermill.unit.Event;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class RedisPersistenceHandlerTest extends PersistenceHandlerTest {
    private static final int maxFetchedInOneTime = 10;
    private static final int maxInsertRetries = 3;
    private static final int TTL = 86400;

    @BeforeClass
    public static void init()  {
        Map<String, Object> persistenceHandlerParams = new HashMap<>();
        persistenceHandlerParams.put(PersistenceHandler.MAX_FETCHED_BULKS_IN_ONE_TIME, maxFetchedInOneTime);
        persistenceHandlerParams.put(PersistenceHandler.MAX_FETCHED_EVENTS_IN_ONE_TIME, maxFetchedInOneTime);
        persistenceHandlerParams.put(PersistenceHandler.MAX_INSERT_TRIES, maxInsertRetries);
        persistenceHandlerParams.put(RedisPersistenceHandler.TTL, TTL);
        persistenceHandlerParams.put(RedisPersistenceHandler.REDIS_SERVICE, new RedisService("localhost", 6379, "", "", "",
                false, 100, 10, 10, 10, 3));
        PersistenceHandlerTest.init(persistenceHandlerParams, "redis");
    }

    @Test
    public void hasFailedBulks() throws InterruptedException, ExecutionException {
        super.hasFailedBulks();
    }

    @Test
    public void fetchFailedBulks() throws InterruptedException, ExecutionException {
        super.fetchFailedBulks();
    }

    @Test
    public void fetchOverflowedEvents() throws InterruptedException, ExecutionException {
        super.fetchOverflowedEvents();
    }

    @Test
    public void fetchedFailedBulksEqualToOriginalOne() throws InterruptedException, ExecutionException {
        super.fetchedFailedBulksEqualToOriginalOne();
    }

    @Test
    public void fetchedOverflowedEventsEqualToOriginalOne() throws InterruptedException, ExecutionException {
        super.fetchedOverflowedEventsEqualToOriginalOne();
    }

    @Test
    public void fetchesCounter() throws InterruptedException, ExecutionException {
        super.fetchesCounter();
    }

    @Test
    public void failedBulksAmount() throws InterruptedException, ExecutionException {
        super.failedBulksAmount();
    }

    @Test
    public void overflowedEventsListsAmount() throws InterruptedException, ExecutionException {
        super.overflowedEventsListsAmount();
    }

    @Test
    public void fetchMaximumBulksAmount() throws InterruptedException, ExecutionException {
        super.fetchMaximumBulksAmount();
    }

    @Test
    public void fetchMaximumEventsAmount() throws InterruptedException, ExecutionException {
        super.fetchMaximumEventsAmount();
    }

    @Test
    public void dropAndRecreateTable() throws InterruptedException, ExecutionException {
        super.dropAndRecreateTable();
    }
}
