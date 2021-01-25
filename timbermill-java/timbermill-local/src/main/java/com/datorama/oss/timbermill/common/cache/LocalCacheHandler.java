package com.datorama.oss.timbermill.common.cache;

import com.datorama.oss.timbermill.common.KamonConstants;
import com.datorama.oss.timbermill.unit.LocalTask;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.Maps;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.datorama.oss.timbermill.ElasticsearchClient.GSON;

public class LocalCacheHandler extends AbstractCacheHandler {
    private Cache<String, String> tasksCache;

    LocalCacheHandler(long maximumTasksCacheWeight, long maximumOrphansCacheWeight) {
        super(maximumOrphansCacheWeight);
        tasksCache = CacheBuilder.newBuilder()
                .maximumWeight(maximumTasksCacheWeight)
                .weigher((Weigher<String, String>) (key, value) -> 2 * (key.length() + value.length()))
                .removalListener(notification -> {
                    String key = notification.getKey();
                    String value = notification.getValue();
                    KamonConstants.TASK_CACHE_SIZE_RANGE_SAMPLER.withoutTags().decrement(2 * (key.length() + value.length()));
                    KamonConstants.TASK_CACHE_ENTRIES_RANGE_SAMPLER.withoutTags().decrement();
                })
                .build();

    }

    @Override
    public Map<String, LocalTask> getFromTasksCache(Collection<String> idsList) {
        Map<String, LocalTask> retMap = Maps.newHashMap();
        for (String id : idsList) {
            String taskString = tasksCache.getIfPresent(id);
            LocalTask localTask = GSON.fromJson(taskString, LocalTask.class);
            retMap.put(id, localTask);
        }
        return retMap;

    }

    @Override
    public void pushToTasksCache(Map<String, LocalTask> idsToMap) {
        for (Map.Entry<String, LocalTask> entry : idsToMap.entrySet()) {
            String id = entry.getKey();
            LocalTask localTask = entry.getValue();
            String taskString = GSON.toJson(localTask);
            tasksCache.put(id, taskString);
            KamonConstants.TASK_CACHE_SIZE_RANGE_SAMPLER.withoutTags().increment(2 * (id.length() + taskString.length()));
            KamonConstants.TASK_CACHE_ENTRIES_RANGE_SAMPLER.withoutTags().increment();
        }
    }
}
