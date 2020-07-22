package com.datorama.oss.timbermill.cron;

import java.util.List;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datorama.oss.timbermill.ElasticsearchClient;
import com.datorama.oss.timbermill.common.disk.DbBulkRequest;
import com.datorama.oss.timbermill.common.disk.DiskHandler;

import static com.datorama.oss.timbermill.common.ElasticsearchUtil.CLIENT;
import static com.datorama.oss.timbermill.common.ElasticsearchUtil.DISK_HANDLER;

public class BulkPersistentFetchJob implements Job {

	private static final Logger LOG = LoggerFactory.getLogger(BulkPersistentFetchJob.class);
	private static boolean currentlyRunning = false;

	@Override public void execute(JobExecutionContext context) {
		if (!currentlyRunning) {
			currentlyRunning = true;
			LOG.info("Cron is fetching from disk...");
			ElasticsearchClient es = (ElasticsearchClient) context.getJobDetail().getJobDataMap().get(CLIENT);
			DiskHandler diskHandler = (DiskHandler) context.getJobDetail().getJobDataMap().get(DISK_HANDLER);
			boolean runNextBulk = true;
			while (runNextBulk) {
				runNextBulk = retryFailedRequestsFromDisk(es, diskHandler);
			}
			currentlyRunning = false;
		}
	}

	private static boolean retryFailedRequestsFromDisk(ElasticsearchClient es, DiskHandler diskHandler) {

		es.dailyResetCounters();

		boolean keepRunning = false;
		LOG.info("Persistence Status: {} persisted to disk, {} re-processed successfully, {} failed after max retries from db since 00:00, {} couldn't be inserted to db since 00:00", es.getNumOfBulksPersistedToDisk(),
				es.getNumOfSuccessfulBulksFromDisk(),
				es.getNumOfFetchedMaxTimes(), es.getNumOfCouldNotBeInserted());
		if (diskHandler.hasFailedBulks()) {
			keepRunning = true;
			int successBulks = 0;
			LOG.info("------------------ Retry Failed-Requests From Disk Start ------------------");
			List<DbBulkRequest> failedRequestsFromDisk = diskHandler.fetchAndDeleteFailedBulks();
			if (failedRequestsFromDisk.size() == 0) {
				keepRunning = false;
			}
			for (DbBulkRequest dbBulkRequest : failedRequestsFromDisk) {
				if (!es.sendDbBulkRequest(dbBulkRequest, 0)) {
					keepRunning = false;
				}
				else {
					successBulks+=1;
				}
			}
			LOG.info("------------------ Retry Failed-Requests From Disk End ({}/{} fetched bulks re-processed successfully) ------------------",successBulks,failedRequestsFromDisk.size());
		} else {
			LOG.info("There are no failed bulks to fetch from disk");
		}
		return keepRunning;
	}
}
