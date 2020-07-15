package com.datorama.oss.timbermill.pipe;

import com.datorama.oss.timbermill.ElasticsearchClient;
import com.datorama.oss.timbermill.ElasticsearchParams;
import com.datorama.oss.timbermill.TaskIndexer;
import com.datorama.oss.timbermill.common.DiskHandler;
import com.datorama.oss.timbermill.common.ElasticsearchUtil;
import com.datorama.oss.timbermill.unit.Event;

import org.elasticsearch.ElasticsearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class LocalOutputPipe implements EventOutputPipe {

    private static final int EVENT_QUEUE_CAPACITY = 1000000;

    private final BlockingQueue<Event> buffer = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);

    private ElasticsearchClient esClient;
    private TaskIndexer taskIndexer;
    private boolean keepRunning = true;
    private boolean stoppedRunning = false;

    private static final Logger LOG = LoggerFactory.getLogger(LocalOutputPipe.class);

    private LocalOutputPipe(Builder builder) {
        if (builder.elasticUrl == null){
            throw new ElasticsearchException("Must enclose an Elasticsearch URL");
        }

        ElasticsearchParams elasticsearchParams = new ElasticsearchParams(builder.pluginsJson, builder.maxCacheSize, builder.maxCacheHoldTimeSeconds,
                builder.numberOfShards, builder.numberOfReplicas,  builder.daysRotation, builder.deletionCronExp, builder.mergingCronExp, builder.maxTotalFields, builder.persistentFetchCronExp
        );

        Map<String, Object> params = DiskHandler.buildDiskHandlerParams(builder.maxFetchedBulksInOneTime, builder.maxInsertTries, builder.locationInDisk);
        esClient = new ElasticsearchClient(builder.elasticUrl, builder.indexBulkSize, builder.indexingThreads, builder.awsRegion, builder.elasticUser, builder.elasticPassword,
                builder.maxIndexAge, builder.maxIndexSizeInGB, builder.maxIndexDocs, builder.numOfElasticSearchActionsTries, builder.maxBulkIndexFetched, builder.searchMaxSize, params,
                builder.diskHandlerStrategy
        );

        taskIndexer = ElasticsearchUtil.bootstrap(elasticsearchParams, esClient);
        startWorkingThread();
    }

    public LocalOutputPipe(ElasticsearchParams elasticsearchParams,ElasticsearchClient es) {
        esClient = es;
        taskIndexer = ElasticsearchUtil.bootstrap(elasticsearchParams, esClient);
        startWorkingThread();
    }

    private void startWorkingThread() {
        Runnable eventsHandler = () -> {
            LOG.info("Timbermill has started");
            while (keepRunning) {
                ElasticsearchUtil.drainAndIndex(buffer, taskIndexer, esClient);
            }
            stoppedRunning = true;
        };

        Thread workingThread = new Thread(eventsHandler);
        workingThread.start();
    }

    @Override
    public void send(Event e){
        buffer.add(e);
    }

    public void close() {
        LOG.info("Gracefully shutting down Timbermill Server.");
        taskIndexer.close();
        keepRunning = false;
        while(!stoppedRunning){
            try {
                Thread.sleep(ElasticsearchUtil.THREAD_SLEEP);
            } catch (InterruptedException ignored) {
            }
        }
        LOG.info("Timbermill server was shut down.");
    }

    @Override public int getCurrentBufferSize() {
        return buffer.size();
    }

    public ElasticsearchClient getEsClient() {
        return esClient;
    }

    public void setEsClient(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public static class Builder {

        //DEFAULTS
        private int searchMaxSize = 1000;
        private int maxBulkIndexFetched = 3;
        private int numOfElasticSearchActionsTries = 3;
        private int maxCacheHoldTimeSeconds = 60;
        private int maxCacheSize = 10000;
        private String elasticUrl = null;
        private String pluginsJson = "[]";
        private int daysRotation = 90;
        private int indexBulkSize = 200000;
        private int indexingThreads = 1;
        private String elasticUser = null;
        private String awsRegion = null;
        private String elasticPassword = null;
        private int numberOfShards = 10;
        private int numberOfReplicas = 1;
        private long maxIndexAge = 7;
        private long maxIndexSizeInGB = 100;
        private int maxTotalFields = 4000;
        private long maxIndexDocs = 1000000000;
        private String deletionCronExp = "0 0 12 1/1 * ? *";
        private String mergingCronExp = "0 0 0/1 1/1 * ? *";
        private String persistentFetchCronExp = "0 0/10 * 1/1 * ? *";
        private String diskHandlerStrategy = "sqlite";
        private int maxFetchedBulksInOneTime = 100;
        private int maxInsertTries = 10;
        private String locationInDisk = "/db";

        public Builder url(String elasticUrl) {
            this.elasticUrl = elasticUrl;
            return this;
        }

        public Builder pluginsJson(String pluginsJson) {
            this.pluginsJson = pluginsJson;
            return this;
        }

        public Builder numberOfShards(int numberOfShards) {
            this.numberOfShards = numberOfShards;
            return this;
        }

        public Builder deletionCronExp(String deletionCronExp) {
            this.deletionCronExp = deletionCronExp;
            return this;
        }

        public Builder numberOfReplicas(int numberOfReplicas) {
            this.numberOfReplicas = numberOfReplicas;
            return this;
        }

        public Builder daysRotation(int daysRotation) {
            this.daysRotation = daysRotation;
            return this;
        }

        public Builder awsRegion(String awsRegion) {
            this.awsRegion = awsRegion;
            return this;
        }

        public Builder indexBulkSize(int indexBulkSize) {
            this.indexBulkSize = indexBulkSize;
            return this;
        }

        public Builder indexingThreads(int indexingThreads) {
            this.indexingThreads = indexingThreads;
            return this;
        }

        public Builder elasticUser(String elasticUser) {
            this.elasticUser = elasticUser;
            return this;
        }

        public Builder elasticPassword(String elasticPassword) {
            this.elasticPassword = elasticPassword;
            return this;
        }

        public Builder maxCacheSize(int maxCacheSize) {
            this.maxCacheSize = maxCacheSize;
            return this;
        }

        public Builder maxCacheHoldTimeMinutes(int maxCacheHoldTimeMinutes) {
            this.maxCacheHoldTimeSeconds = maxCacheHoldTimeMinutes;
            return this;
        }

        public Builder maxIndexAge(long maxIndexAge) {
            this.maxIndexAge = maxIndexAge;
            return this;
        }

        public Builder maxIndexSizeInGB(long maxIndexSizeInGB) {
            this.maxIndexSizeInGB = maxIndexSizeInGB;
            return this;
        }

        public Builder maxIndexDocs(long maxIndexDocs) {
            this.maxIndexDocs = maxIndexDocs;
            return this;
        }

        public Builder mergingCronExp(String mergingCronExp) {
            this.mergingCronExp = mergingCronExp;
            return this;
        }

        public Builder numOfElasticSearchActionsTries(int numOfElasticSearchActionsTries) {
            this.numOfElasticSearchActionsTries = numOfElasticSearchActionsTries;
            return this;
        }

        public Builder diskHandlerStrategy(String diskHandlerStrategy) {
            this.diskHandlerStrategy = diskHandlerStrategy;
            return this;
        }

        public Builder maxFetchedBulksInOneTime(int maxFetchedBulksInOneTime) {
            this.maxFetchedBulksInOneTime = maxFetchedBulksInOneTime;
            return this;
        }

        public Builder maxInsertTries(int maxInsertTries) {
            this.maxInsertTries = maxInsertTries;
            return this;
        }

        public Builder locationInDisk(String locationInDisk) {
            this.locationInDisk = locationInDisk;
            return this;
        }

        public Builder maxBulkIndexFetched(int maxBulkIndexFetched) {
            this.maxBulkIndexFetched = maxBulkIndexFetched;
            return this;
        }

        public Builder searchMaxSize(int searchMaxSize) {
            this.searchMaxSize = searchMaxSize;
            return this;
        }

        public Builder persistentFetchCronExp(String persistentFetchCronExp) {
            this.persistentFetchCronExp = persistentFetchCronExp;
            return this;
        }

        public LocalOutputPipe build() {
            return new LocalOutputPipe(this);
        }

    }
}
