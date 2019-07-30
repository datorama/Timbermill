package com.datorama.timbermill;


import com.datorama.timbermill.common.Constants;
import com.datorama.timbermill.plugins.PluginsConfig;
import com.datorama.timbermill.plugins.TaskLogPlugin;
import com.datorama.timbermill.unit.*;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.elasticsearch.ElasticsearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import static com.datorama.timbermill.common.Constants.GSON;
import static com.datorama.timbermill.unit.Task.TaskStatus;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

public class LocalTaskIndexer {

    private final ElasticsearchClient es;
    private BlockingQueue<Event> eventsQueue = new ArrayBlockingQueue<>(1000000);
    private static final Logger LOG = LoggerFactory.getLogger(LocalTaskIndexer.class);
    private Collection<TaskLogPlugin> logPlugins;
    private Map<String, Integer> propertiesLengthMap;
    private int defaultMaxChars;
    private boolean keepRunning = true;
    private boolean stoppedRunning = false;
    private int maxEventsToDrainFromQueue;

    public LocalTaskIndexer(String pluginsJson, Map<String, Integer> propertiesLengthMap, int defaultMaxChars, ElasticsearchClient es, int secondBetweenPolling, int maxEventsToDrainFromQueue) {
        logPlugins = PluginsConfig.initPluginsFromJson(pluginsJson);
        this.propertiesLengthMap = propertiesLengthMap;
        this.defaultMaxChars = defaultMaxChars;
        this.maxEventsToDrainFromQueue = maxEventsToDrainFromQueue;
        this.es = es;
        new Thread(() -> {
            indexMetadataTaskToTimbermill();
            try {
                while (keepRunning) {
                    long l1 = System.currentTimeMillis();
                    try {
                        retrieveAndIndex();
                    } catch (RuntimeException e) {
                        LOG.error("Error was thrown from TaskIndexer:", e);
                    } finally {
                        long l2 = System.currentTimeMillis();
                        long timeToSleep = (secondBetweenPolling * 1000) - (l2 - l1);
                        Thread.sleep(Math.max(timeToSleep, 0));
                    }
                }
                stoppedRunning = true;
            }catch (InterruptedException ignore){
                LOG.info("Timbermill server was interrupted, exiting");
            }
        }).start();
    }

    public void close() {
        keepRunning = false;
        while(!stoppedRunning){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {

            }
        }
        es.close();
    }

    private void retrieveAndIndex() {
        LOG.info("------------------ Batch Start ------------------");
        List<Event> events = new ArrayList<>();
        eventsQueue.drainTo(events, maxEventsToDrainFromQueue);
        ZonedDateTime taskIndexerStartTime = ZonedDateTime.now();
        trimAllStrings(events);
        List<Event> heartbeatEvents = events.stream().filter(e -> (e.getName() != null) && e.getName().equals(Constants.HEARTBEAT_TASK)).collect(toList());
        for (Event e: heartbeatEvents){
            HeartbeatEvent heartbeatEvent = new HeartbeatEvent(e);
            this.es.indexMetaDataEvent(e.getTime(), GSON.toJson(heartbeatEvent));
        }

        List<Event> timbermillEvents = events.stream().filter(e -> (e.getName() == null) || !e.getName().equals(Constants.HEARTBEAT_TASK)).collect(toList());

        LOG.info("{} events retrieved from pipe", timbermillEvents.size());
        Set<String> missingParentEvents = getMissingParentEvents(timbermillEvents);

        Map<String, Task> previouslyIndexedTasks = new HashMap<>();
        try {
            if (!missingParentEvents.isEmpty()) {
                int missingParentNum = missingParentEvents.size();
                LOG.info("{} missing parent events for current batch", missingParentNum);
                previouslyIndexedTasks = this.es.fetchIndexedTasks(missingParentEvents);
                if (missingParentNum == previouslyIndexedTasks.size()) {
                    LOG.info("All {} missing event were retrieved from elasticsearch..", missingParentNum);
                } else {
                    Sets.SetView<String> notFoundEvents = Sets.difference(missingParentEvents, previouslyIndexedTasks.keySet());

                    for (String notFoundEvent : notFoundEvents) {
                        for (Event event : timbermillEvents) {
                            if (notFoundEvent.equals(event.getPrimaryId())){
                                event.setPrimaryId(null);
                            }
                        }
                    }


                    String joinString = StringUtils.join(notFoundEvents, ",");
                    LOG.warn("{} missing events were not retrieved from elasticsearch. Events [{}]", missingParentNum - previouslyIndexedTasks.size(), joinString);
                }
            }

            long pluginsDuration = applyPlugins(timbermillEvents);

            enrichEvents(timbermillEvents, previouslyIndexedTasks);
            es.index(timbermillEvents);
            if (!timbermillEvents.isEmpty()) {
                LOG.info("{} tasks were indexed to elasticsearch", timbermillEvents.size());
                indexMetadataTask(taskIndexerStartTime, timbermillEvents.size(), missingParentEvents.size(), pluginsDuration);
            }
            LOG.info("------------------ Batch End ------------------");
        } catch (ElasticsearchException e){
            indexFailedMetadataTask(taskIndexerStartTime, timbermillEvents.size(), previouslyIndexedTasks.size(), e);
        }
    }

    private void enrichEvents(List<Event> events, Map<String, Task> previouslyIndexedTasks) {

        /*
         * Compute origins and down merge parameters from parent
         */
        List<Event> startEvents = events.stream().filter(e -> e.isStartEvent()).collect(toList());
        for (Event event : startEvents) {
            List<String> parentsPath = new ArrayList<>();
            String parentId = event.getParentId();
            if (parentId != null) {
                ParentProperties parentProperties = getParentProperties(previouslyIndexedTasks, events.stream().collect(groupingBy(e -> e.getTaskId())), parentId);

                String primaryId = parentProperties.getPrimaryId();
                if (primaryId == null && parentId == null){
                    event.setPrimaryId(event.getTaskId());
                }
                else {
                    event.setPrimaryId(primaryId);
                    for (Map.Entry<String, String> entry : parentProperties.getContex().entrySet()) {
                        event.getContext().putIfAbsent(entry.getKey(), entry.getValue());
                    }

                    List<String> parentParentsPath = parentProperties.getParentPath();
                    if((parentParentsPath != null) && !parentParentsPath.isEmpty()) {
                        parentsPath.addAll(parentParentsPath);
                    }

                    String parentName = parentProperties.getParentName();
                    if(parentName != null) {
                        parentsPath.add(parentName);
                    }
                }
            }
            if(!parentsPath.isEmpty()) {
                event.setParentsPath(parentsPath);
            }
        }
    }

    public void addEvent(Event e) {
        eventsQueue.add(e);
    }

    private long applyPlugins(List<Event> events) {
        long pluginsStart = System.currentTimeMillis();
        try {
            for (TaskLogPlugin plugin : logPlugins) {
                ZonedDateTime startTime = ZonedDateTime.now();
                TaskStatus status;
                String exception = null;
                try {
                    plugin.apply(events);
                    status = TaskStatus.SUCCESS;
                } catch (Exception ex) {
                    exception = ExceptionUtils.getStackTrace(ex);
                    status = TaskStatus.ERROR;
                    LOG.error("error in plugin" + plugin, ex);
                }
                ZonedDateTime endTime = ZonedDateTime.now();
                long duration = endTime.toInstant().toEpochMilli() - startTime.toInstant().toEpochMilli();
                PluginApplierEvent pluginApplierEvent = new PluginApplierEvent(startTime, plugin.getName(), plugin.getClass().getSimpleName(), status, exception, endTime, duration);
                es.indexMetaDataEvent(startTime, GSON.toJson(pluginApplierEvent));
            }
        } catch (Exception ex) {
            LOG.error("Error running plugins", ex);
        }
        long pluginsEnd = System.currentTimeMillis();
        return pluginsEnd - pluginsStart;
    }

    private void trimAllStrings(List<Event> events) {
        events.forEach(e -> {
            e.setStrings(getTrimmedLongValues(e.getStrings(), "string"));
            e.setTexts(getTrimmedLongValues(e.getTexts(), "text"));
            e.setContext(getTrimmedLongValues(e.getContext(), "ctx"));
        });
    }

    private Map<String, String> getTrimmedLongValues(Map<String, String> oldMap, String prefix) {
        Map<String, String> newMap = new HashMap<>();
        for (Map.Entry<String, String> entry : oldMap.entrySet()){
            String key = entry.getKey();
            String value = trimIfNeededValue(prefix + "." + key, entry.getValue());
            newMap.put(key, value);
        }
        return newMap;
    }

    private String trimIfNeededValue(String key, String value) {
        Integer maxPropertyLength = propertiesLengthMap.get(key);
        if (maxPropertyLength != null){
            if (value.length() > maxPropertyLength) {
                return value.substring(0, maxPropertyLength);
            }
        }
        else if (value.length() > defaultMaxChars){
            return value.substring(0, defaultMaxChars);
        }
        return value;
    }

    private void indexFailedMetadataTask(ZonedDateTime taskIndexerStartTime, Integer eventsAmount,
                                         Integer fetchedAmount, Exception e) {
        ZonedDateTime taskIndexerEndTime = ZonedDateTime.now();
        long duration = taskIndexerEndTime.toInstant().toEpochMilli() - taskIndexerStartTime.toInstant().toEpochMilli();

        IndexEvent indexEvent = new IndexEvent(eventsAmount, fetchedAmount, taskIndexerStartTime, taskIndexerEndTime, duration, TaskStatus.ERROR, ExceptionUtils.getStackTrace(e), null);
        es.indexMetaDataEvent(taskIndexerStartTime, GSON.toJson(indexEvent));
    }

    private void indexMetadataTask(ZonedDateTime taskIndexerStartTime, Integer eventsAmount, Integer fetchedAmount, long pluginsDuration) {

        ZonedDateTime taskIndexerEndTime = ZonedDateTime.now();
        long duration = taskIndexerEndTime.toInstant().toEpochMilli() - taskIndexerStartTime.toInstant().toEpochMilli();

        IndexEvent indexEvent = new IndexEvent(eventsAmount, fetchedAmount, taskIndexerStartTime, taskIndexerEndTime, duration, TaskStatus.SUCCESS, null, pluginsDuration);
        es.indexMetaDataEvent(taskIndexerStartTime, GSON.toJson(indexEvent));
    }

    private static Set<String> getMissingParentEvents(Collection<Event> events) {
        Set<String> allEventParentTasks = events.stream()
                .filter(e -> e.getParentId() != null)
                .map(e -> e.getParentId()).collect(Collectors.toCollection(LinkedHashSet::new));

        return Sets.difference(allEventParentTasks, getAllTaskIds(events));
    }

    private static Set<String> getAllTaskIds(Collection<Event> events) {
        return events.stream().filter(e -> e.isStartEvent()).map(e -> e.getTaskId()).collect(Collectors.toSet());
    }

    private static ParentProperties getParentProperties(Map<String, Task> previouslyIndexedTasks, Map<String, List<Event>> currentBatchEvents, String parentId) {

        Map<String, String> context = Maps.newHashMap();
        String primaryId = null;
        List<String> parentPath = null;
        String parentName = null;
        Task indexedTask = previouslyIndexedTasks.get(parentId);
        if (indexedTask != null){
            primaryId = indexedTask.getPrimaryId();
            context = indexedTask.getCtx();
            parentPath = indexedTask.getParentsPath();
            parentName = indexedTask.getName();
        }

        List<Event> previousEvents = currentBatchEvents.get(parentId);
        if (previousEvents != null){
            for (Event previousEvent : previousEvents) {
                String previousPrimaryId = previousEvent.getPrimaryId();
                if (previousPrimaryId != null){
                    primaryId = previousPrimaryId;
                }
                Map<String, String> previousContext = previousEvent.getContext();
                if (previousContext != null){
                    context.putAll(previousContext);

                }
                List<String> previousPath = previousEvent.getParentsPath();
                if (previousPath != null){
                    parentPath = previousPath;
                }

                String previousName = previousEvent.getName();
                if (previousName != null){
                    parentName = previousName;
                }
            }
        }
        return new ParentProperties(primaryId, context, parentPath, parentName);
    }

    private void indexMetadataTaskToTimbermill() {
        ZonedDateTime time = ZonedDateTime.now();
        LocalStartupEvent localStartupEvent = new LocalStartupEvent(time);
        es.indexMetaDataEvent(time, GSON.toJson(localStartupEvent));
    }

    private static class ParentProperties{

        private final String primaryId;
        private final Map<String, String> context;
        private final List<String> parentPath;
        private String parentName;

        ParentProperties(String primaryId, Map<String, String> context, List<String> parentPath, String parentName) {
            this.primaryId = primaryId;
            this.context = context;
            this.parentPath = parentPath;
            this.parentName = parentName;
        }

        String getPrimaryId() {
            return primaryId;
        }

        Map<String, String> getContex() {
            return context;
        }

        List<String> getParentPath() {
            return parentPath;
        }

        String getParentName() {
            return parentName;
        }
    }
}
