package com.datorama.timbermill.server.service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.datorama.oss.timbermill.ElasticsearchClient;
import com.datorama.oss.timbermill.ElasticsearchParams;
import com.datorama.oss.timbermill.TaskIndexer;
import com.datorama.oss.timbermill.common.DiskHandler;
import com.datorama.oss.timbermill.common.ElasticsearchUtil;
import com.datorama.oss.timbermill.unit.Event;

@Service
public class TimbermillService {

	private static final Logger LOG = LoggerFactory.getLogger(TimbermillService.class);
	private static final int THREAD_SLEEP = 2000;
	private TaskIndexer taskIndexer;
	private BlockingQueue<Event> eventsQueue;

	private boolean keepRunning = true;
	private boolean stoppedRunning = false;
	private long terminationTimeout;

	@Autowired
	public TimbermillService(@Value("${INDEX_BULK_SIZE:200000}") Integer indexBulkSize,
			@Value("${ELASTICSEARCH_URL:http://localhost:9200}") String elasticUrl,
			@Value("${ELASTICSEARCH_AWS_REGION:}") String awsRegion,
			@Value("${ELASTICSEARCH_USER:}") String elasticUser,
			@Value("${ELASTICSEARCH_PASSWORD:}") String elasticPassword,
			@Value("${ELASTICSEARCH_NUMBER_OF_SHARDS:10}") int numberOfShards,
			@Value("${ELASTICSEARCH_NUMBER_OF_REPLICAS:1}") int numberOfReplicas,
			@Value("${ELASTICSEARCH_INDEX_MAX_AGE:7}") int maxIndexAge,
			@Value("${ELASTICSEARCH_INDEX_MAX_GB_SIZE:100}") int maxIndexSizeInGB,
			@Value("${ELASTICSEARCH_INDEX_MAX_DOCS:1000000000}") int maxIndexDocs,
			@Value("${ELASTICSEARCH_MAX_TOTAL_FIELDS:4000}") int maxTotalFields,
			@Value("${ELASTICSEARCH_MAX_SEARCH_SIZE:1000}") int searchMaxSize,
			@Value("${ELASTICSEARCH_ACTION_TRIES:3}") int numOfElasticSearchActionsTries,
			@Value("${INDEXING_THREADS:10}") int indexingThreads,
			@Value("${DAYS_ROTATION:90}") Integer daysRotation,
			@Value("${TERMINATION_TIMEOUT_SECONDS:60}") int terminationTimeoutSeconds,
			@Value("${PLUGINS_JSON:[]}") String pluginsJson,
			@Value("${CACHE_MAX_SIZE:1000000}") int maximumCacheSize,
			@Value("${EVENT_QUEUE_CAPACITY:10000000}") int eventsQueueCapacity,
			@Value("${MAX_BULK_INDEX_FETCHES:3}") int maxBulkIndexFetches,
			@Value("${MERGING_CRON_EXPRESSION:0 0 0/1 1/1 * ? *}") String mergingCronExp,
			@Value("${DELETION_CRON_EXPRESSION:0 0 12 1/1 * ? *}") String deletionCronExp,
			@Value("${CACHE_MAX_HOLD_TIME_MINUTES:6}") int maximumCacheMinutesHold,
			@Value("${DISK_HANDLER_STRATEGY:sqlite}") String diskHandlerStrategy,
			@Value("${PERSISTENT_FETCH_CRON_EXPRESSION:0 0/10 * 1/1 * ? *}") String persistentFetchCronExp,
			@Value("${MAX_FETCHED_BULKS_IN_ONE_TIME:100}") int maxFetchedBulksInOneTime,
			@Value("${MAX_INSERT_TRIES:10}") int maxInsertTries,
			@Value("${LOCATION_IN_DISK:/db}") String locationInDisk) {

		eventsQueue = new LinkedBlockingQueue<>(eventsQueueCapacity);
		terminationTimeout = terminationTimeoutSeconds * 1000;
		ElasticsearchParams elasticsearchParams = new ElasticsearchParams(pluginsJson, maximumCacheSize, maximumCacheMinutesHold, numberOfShards,
				numberOfReplicas, daysRotation, deletionCronExp, mergingCronExp, maxTotalFields, persistentFetchCronExp);

		Map<String, Object> params = DiskHandler.buildDiskHandlerParams(maxFetchedBulksInOneTime, maxInsertTries, locationInDisk);
		ElasticsearchClient elasticsearchClient = new ElasticsearchClient(elasticUrl, indexBulkSize, indexingThreads, awsRegion, elasticUser,
				elasticPassword, maxIndexAge, maxIndexSizeInGB, maxIndexDocs, numOfElasticSearchActionsTries, maxBulkIndexFetches, searchMaxSize, params, diskHandlerStrategy
		);

		taskIndexer = ElasticsearchUtil.bootstrap(elasticsearchParams, elasticsearchClient);
		startWorkingThread(elasticsearchClient);
	}

	private void startWorkingThread(ElasticsearchClient es) {
		Runnable eventsHandler = () -> {
			LOG.info("Timbermill has started");
			while (keepRunning) {
				ElasticsearchUtil.drainAndIndex(eventsQueue, taskIndexer, es);
			}
			stoppedRunning = true;
		};

		Thread workingThread = new Thread(eventsHandler);
		workingThread.start();
	}

	@PreDestroy
	public void tearDown(){
		LOG.info("Gracefully shutting down Timbermill Server.");
		keepRunning = false;
		long currentTimeMillis = System.currentTimeMillis();
		while(!stoppedRunning && !reachTerminationTimeout(currentTimeMillis)){
			try {
				Thread.sleep(THREAD_SLEEP);
			} catch (InterruptedException ignored) {}
		}
		taskIndexer.close();
		LOG.info("Timbermill server was shut down.");
	}

	private boolean reachTerminationTimeout(long starTime) {
		boolean reachTerminationTimeout = System.currentTimeMillis() - starTime > terminationTimeout;
		if (reachTerminationTimeout){
			LOG.warn("Timbermill couldn't gracefully shutdown in {} seconds, was killed with {} events in internal buffer", terminationTimeout / 1000, eventsQueue.size());
		}
		return reachTerminationTimeout;
	}

	void handleEvent(Collection<Event> events){
		for (Event event : events) {
			if(!this.eventsQueue.offer(event)){
				LOG.warn("Event {} was removed from the queue due to insufficient space", event.getTaskId());
			}
		}
	}

	int getEventsQueueSize() {
		return eventsQueue.size();
	}

	TaskIndexer getTaskIndexer() {
		return taskIndexer;
	}
}
