package com.datorama.oss.timbermill.common.redis;

import com.datorama.oss.timbermill.unit.LocalTask;
import com.datorama.oss.timbermill.unit.TaskMetaData;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;
import com.esotericsoftware.kryo.util.Pool;
import com.evanlennick.retry4j.CallExecutorBuilder;
import com.evanlennick.retry4j.Status;
import com.evanlennick.retry4j.config.RetryConfig;
import com.evanlennick.retry4j.config.RetryConfigBuilder;
import com.evanlennick.retry4j.exception.RetriesExhaustedException;
import com.github.jedis.lock.JedisLock;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

import java.io.ByteArrayOutputStream;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;

public class RedisService {

    private static final Logger LOG = LoggerFactory.getLogger(RedisService.class);

    private final JedisPool jedisPool;
    private final Pool<Kryo> kryoPool;
    private final RetryConfig retryConfig;
    private int redisTtlInSeconds;
    private int redisGetSize;
    private int redisMaxTries;

    public RedisService(RedisServiceConfig redisServiceConfig) {
        redisTtlInSeconds = redisServiceConfig.getRedisTtlInSeconds();
        redisGetSize = redisServiceConfig.getRedisGetSize();
        redisMaxTries = redisServiceConfig.getRedisMaxTries();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisServiceConfig.getRedisPoolMaxTotal());
        poolConfig.setMinIdle(redisServiceConfig.getRedisPoolMinIdle());
        poolConfig.setMaxIdle(redisServiceConfig.getRedisPoolMaxIdle());
        poolConfig.setTestOnBorrow(true);

        String redisHost = redisServiceConfig.getRedisHost();
        int redisPort = redisServiceConfig.getRedisPort();
        boolean redisUseSsl = redisServiceConfig.isRedisUseSsl();
        String redisPass = redisServiceConfig.getRedisPass();
        if (StringUtils.isEmpty(redisPass)) {
            jedisPool = new JedisPool(poolConfig, redisHost, redisPort, Protocol.DEFAULT_TIMEOUT, redisUseSsl);
        } else {
            jedisPool = new JedisPool(poolConfig, redisHost, redisPort, Protocol.DEFAULT_TIMEOUT, redisPass, redisUseSsl);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String redisMaxMemory = redisServiceConfig.getRedisMaxMemory();
            if (!StringUtils.isEmpty(redisMaxMemory)) {
                jedis.configSet("maxmemory", redisMaxMemory);
            }
            String redisMaxMemoryPolicy = redisServiceConfig.getRedisMaxMemoryPolicy();
            if (!StringUtils.isEmpty(redisMaxMemoryPolicy)) {
                jedis.configSet("maxmemory-policy", "allkeys-lru");
            }
        }

        kryoPool = new Pool<Kryo>(true, false, 10) {
            protected Kryo create() {
                Kryo kryo = new Kryo();
                kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
                kryo.register(LocalTask.class);
                kryo.register(java.util.HashMap.class);
                kryo.register(java.util.ArrayList.class);
                kryo.register(TaskMetaData.class);
                kryo.register(java.time.ZonedDateTime.class);
                kryo.register(com.datorama.oss.timbermill.unit.TaskStatus.class);
                kryo.register(com.datorama.oss.timbermill.unit.SpotEvent.class);
                kryo.register(com.datorama.oss.timbermill.unit.InfoEvent.class);
                kryo.register(com.datorama.oss.timbermill.unit.SuccessEvent.class);
                kryo.register(com.datorama.oss.timbermill.unit.ErrorEvent.class);
                kryo.register(com.datorama.oss.timbermill.unit.StartEvent.class);
                kryo.register(byte[].class);
                kryo.register(com.datorama.oss.timbermill.common.persistence.DbBulkRequest.class);
                kryo.register(org.elasticsearch.action.bulk.BulkRequest.class, new BulkRequestSerializer());
                return kryo;
            }
        };
        retryConfig = new RetryConfigBuilder()
                .withMaxNumberOfTries(redisMaxTries)
                .retryOnAnyException()
                .withDelayBetweenTries(1, ChronoUnit.SECONDS)
                .withExponentialBackoff()
                .build();
        LOG.info("Connected to Redis");
    }

    // region HASH

    public <T> Map<String, T> getFromRedis(Collection<String> keys) {
        Map<String, T> retMap = Maps.newHashMap();
        for (List<String> keysPartition : Iterables.partition(keys, redisGetSize)) {
            byte[][] keysPartitionArray = new byte[keysPartition.size()][];
            for (int i = 0; i < keysPartition.size(); i++) {
                keysPartitionArray[i] = keysPartition.get(i).getBytes();
            }
            try (Jedis jedis = jedisPool.getResource()) {
                List<byte[]> serializedObjects = runWithRetries(() -> jedis.mget(keysPartitionArray), "MGET Keys");
                for (int i = 0; i < keysPartitionArray.length; i++) {
                    byte[] serializedObject = serializedObjects.get(i);

                    if (serializedObject == null || serializedObject.length == 0) {
                        LOG.warn("Key {} doesn't exist (could have been expired).", keysPartition.get(i));
                        continue;
                    }
                    Kryo kryo = kryoPool.obtain();
                    try {
                        T object = (T) kryo.readClassAndObject(new Input(serializedObject));
                        String id = new String(keysPartitionArray[i]);
                        retMap.put(id, object);
                    } catch (Exception e) {
                        LOG.error("Error getting keys from Redis. Keys: " + keysPartition, e);
                    } finally {
                        kryoPool.free(kryo);
                    }

                }
            } catch (Exception e) {
                LOG.error("Error getting keys from Redis. Keys: " + keysPartition, e);
            }
        }
        return retMap;
    }

    public void deleteFromRedis(Collection<String> keys) {
        for (List<String> keysPartition : Iterables.partition(keys, redisGetSize)) {
            try (Jedis jedis = jedisPool.getResource()) {
                String[] keysPartitionArray = new String[keysPartition.size()];
                keysPartition.toArray(keysPartitionArray);
                runWithRetries(() -> jedis.del(keysPartitionArray), "DEL");
            } catch (Exception e) {
                LOG.error("Error deleting ids from Redis. Ids: " + keysPartition, e);
            }
        }
    }

    public <T> boolean pushToRedis(Map<String, T> keysToValuesMap) {
        return pushToRedis(keysToValuesMap, redisTtlInSeconds);
    }

    public <T> boolean pushToRedis(Map<String, T> keysToValuesMap, Integer redisTtlInSeconds) {
        int ttl = redisTtlInSeconds != null ? redisTtlInSeconds : this.redisTtlInSeconds;
        boolean allPushed = true;
        try (Jedis jedis = jedisPool.getResource(); Pipeline pipelined = jedis.pipelined()) {
            for (Map.Entry<String, T> entry : keysToValuesMap.entrySet()) {
                String key = entry.getKey();
                T object = entry.getValue();

                try {
                    byte[] taskByteArr = getBytes(object);
                    runWithRetries(() -> pipelined.setex(key.getBytes(), ttl, taskByteArr), "SETEX");
                } catch (Exception e) {
                    allPushed = false;
                    LOG.error("Error pushing key " + key + " to Redis.", e);
                }
            }
        }
        return allPushed;
    }

    // endregion

    // region SORTED SET

    public boolean pushToRedisSortedSet(String setName, String value, double score) {
        boolean pushed = false;
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                runWithRetries(() -> jedis.zadd(setName, score, value), "ZADD");
                pushed = true;
            } catch (Exception e) {
                LOG.error("Error pushing item to Redis " + setName + " sorted set.", e);
            }
        }
        return pushed;
    }


    public List<String> popRedisSortedSet(String setName, int amount) {
        List<String> ret = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                Set<Tuple> tuples = runWithRetries(() -> jedis.zpopmin(setName, amount), "ZPOPMIN");

                for (Tuple tuple : tuples) {
                    String element = tuple.getElement();
                    ret.add(element);
                }
            } catch (Exception e) {
                LOG.error("Error pushing item to Redis " + setName + " sorted set.", e);
            }
        }
        return ret;
    }

    public long getSortedSetSize(String setName) {
        return getSortedSetSize(setName, "-inf", "inf");
    }

    public long getSortedSetSize(String setName, String min, String max) {
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                return runWithRetries(() -> jedis.zcount(setName, min, max), "ZCOUNT");
            } catch (Exception e) {
                LOG.error("Error returning Redis " + setName + " list length", e);
                return -1;
            }
        }
    }

        public Double getMinSocre(String setName) {
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                Set<Tuple> tuples = runWithRetries(() -> jedis.zrangeWithScores(setName, 0, 0), "ZRANGE");
                if (tuples.isEmpty()) {
                    return null;
                } else {
                    return tuples.iterator().next().getScore();
                }
            } catch (Exception e) {
                LOG.error("Error returning Redis " + setName + " list length", e);
                return null;
            }
        }
    }

    // endregion

    // region LIST

    public boolean pushToRedisList(String listName, String value) {
        boolean pushed = false;
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                runWithRetries(() -> jedis.rpush(listName, value), "RPUSH");
                pushed = true;
            } catch (Exception e) {
                LOG.error("Error pushing item to Redis " + listName + " list", e);
            }
        }
        return pushed;
    }

    public long getListLength(String listName) {
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                return runWithRetries(() -> jedis.llen(listName), "LLEN");
            } catch (Exception e) {
                LOG.error("Error returning Redis " + listName + " list length", e);
                return -1;
            }
        }
    }

    public List<String> getRangeFromRedisList(String listName, int start, int end) {
        List<String> elements = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> values = runWithRetries(() -> jedis.lrange(listName, start, end), "LRANGE");
            for (String value : values) {
                if (value == null || value.length() == 0) {
                    continue;
                }
                elements.add(value);
            }
        } catch (Exception e) {
            LOG.error("Error getting elements from Redis " + listName + " list", e);
            elements = new ArrayList<>();
        }
        return elements;
    }

    private void trimRedisList(String listName, int start, int end) {
        try (Jedis jedis = jedisPool.getResource()) {
            runWithRetries(() -> jedis.ltrim(listName, start, end), "TRIM");
        } catch (Exception e) {
            LOG.error("Error trimming Redis " + listName + " list", e);
        }
    }

    // endregion

    public JedisLock lock(String lockName) {
        JedisLock lock = new JedisLock(lockName, 20000, 20000);
        try (Jedis jedis = jedisPool.getResource()) {
            lock.acquire(jedis);
        } catch (Exception e) {
            LOG.error("Error while locking lock {} in Redis", lockName, e);
        }
        return lock;
    }

    public void release(JedisLock lock) {
        try (Jedis jedis = jedisPool.getResource()) {
            lock.release(jedis);
        } catch (Exception e) {
            LOG.error("Error while releasing lock {} in Redis", lock.getLockKey(), e);
        }
    }

    public void close() {
        jedisPool.close();
    }

    public boolean isConnected() {
        return !jedisPool.isClosed();
    }

    // endregion


    // region private methods

    private byte[] getBytes(Object object) {
        ByteArrayOutputStream objStream = new ByteArrayOutputStream();
        Output objOutput = new Output(objStream);

        Kryo kryo = kryoPool.obtain();
        try {
            kryo.writeClassAndObject(objOutput, object);
            objOutput.close();
            return objStream.toByteArray();
        } finally {
            kryoPool.free(kryo);
        }
    }

    private <T> T runWithRetries(Callable<T> callable, String functionDescription) throws RetriesExhaustedException {
        Status<T> status = new CallExecutorBuilder<T>()
                .config(retryConfig)
                .onFailureListener(this::printFailWarning)
                .build()
                .execute(callable, functionDescription);
        return status.getResult();
    }

    private void printFailWarning(Status status) {
        LOG.warn("Failed try # " + status.getTotalTries() + "/" + redisMaxTries + " for [Redis - " + status.getCallName() + "] ", status.getLastExceptionThatCausedRetry());
    }

    // endregion

}