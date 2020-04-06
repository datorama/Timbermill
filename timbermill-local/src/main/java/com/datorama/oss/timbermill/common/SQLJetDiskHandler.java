package com.datorama.oss.timbermill.common;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.sqljet.core.SqlJetException;
import org.tmatesoft.sqljet.core.SqlJetTransactionMode;
import org.tmatesoft.sqljet.core.table.ISqlJetCursor;
import org.tmatesoft.sqljet.core.table.ISqlJetTable;
import org.tmatesoft.sqljet.core.table.SqlJetDb;

import com.datorama.oss.timbermill.common.exceptions.MaximunInsertTriesException;

public class SQLJetDiskHandler implements DiskHandler {
	private static final String DB_NAME = "timbermillJet.db";
	private static final String FAILED_BULKS_TABLE_NAME = "failed_bulks";
	private static final String ID = "id";
	private static final String FAILED_TASK = "failedTask";
	private static final String INSERT_TIME = "insertTime";
	private static final String TIMES_FETCHED = "timesFetched";
	private static final String INSERT_TIME_INDEX = "insertTimeIndex";
	private static final String CREATE_TABLE =
			"CREATE TABLE IF NOT EXISTS " + FAILED_BULKS_TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + FAILED_TASK + " BLOB NOT NULL, " + INSERT_TIME + " TEXT, "
					+ TIMES_FETCHED + " INTEGER)";
	private static final String createInsertTimeIndexQuery = "CREATE INDEX IF NOT EXISTS " + INSERT_TIME_INDEX + " ON " + FAILED_BULKS_TABLE_NAME + "(" +  INSERT_TIME + ")";
	private static final Logger LOG = LoggerFactory.getLogger(SQLJetDiskHandler.class);

	private int waitingTimeInMinutes;
	private int maxFetchedBulksInOneTime;
	private int maxInsertTries;
	private String locationInDisk;
	private SqlJetDb db;
	private ISqlJetTable table;

	public SQLJetDiskHandler(String locationInDisk) {
		this(1,10,3,locationInDisk);
	}

	public SQLJetDiskHandler(int waitingTimeInMinutes, int maxFetchedBulks, int maxInsertTries, String locationInDisk) {
		this.waitingTimeInMinutes = waitingTimeInMinutes;
		this.maxFetchedBulksInOneTime = maxFetchedBulks;
		this.maxInsertTries = maxInsertTries;
		this.locationInDisk = locationInDisk;
		init();
	}

	private void init(){
		// initializing database
		if (!StringUtils.isEmpty(locationInDisk)) {
			this.locationInDisk+="/";
		}
		File dbFile = new File(locationInDisk+DB_NAME);
		try {
			// creating database if not exists
			db = SqlJetDb.open(dbFile, true);
			if (!db.getOptions().isAutovacuum()){
				db.getOptions().setAutovacuum(true);
			}

			// creating table if not exists
			db.beginTransaction(SqlJetTransactionMode.WRITE);
			db.getOptions().setUserVersion(1);
			db.createTable(CREATE_TABLE);
			table = db.getTable(FAILED_BULKS_TABLE_NAME);

			// creating index if not exists
			db.createIndex(createInsertTimeIndexQuery);
			LOG.info("SQLite was created successfully: " + CREATE_TABLE);
			silentDbCommit();
		} catch (Exception e) {
			LOG.error("Creation of DB has failed",e);
			silentCloseDb();
		}
	}

	//region public methods

	@Override
	public List<DbBulkRequest> fetchAndDeleteFailedBulks() {
		return fetchFailedBulks(true);
	}

	@Override
	public void persistToDisk(DbBulkRequest dbBulkRequest) throws MaximunInsertTriesException{
		persistToDisk(dbBulkRequest,1000);
	}

	@Override
	public boolean hasFailedBulks()  {
		boolean returnValue = false;
		ISqlJetCursor resultCursor = null;
		try {
			db.beginTransaction(SqlJetTransactionMode.READ_ONLY);
			resultCursor = getBulksInScopeIterator(); // bulks which are in db at least the waitingTime
			returnValue = !resultCursor.eof();
		} catch (SqlJetException e) {
			e.printStackTrace();
		} finally {
			closeCursor(resultCursor);
			return returnValue;
		}
	}

	@Override
	public boolean isCreatedSuccefully() {
		boolean ret = db != null;
		if (!ret){
			LOG.error("SQLite wasn't initialized successfully.");
		}
		return ret;
	}

	public void updateBulk(DbBulkRequest dbBulkRequest) {
		ISqlJetCursor updateCursor = null;
		int id = dbBulkRequest.getId();
		try {
			LOG.info("Updating bulk with id: {}",id);
			db.beginTransaction(SqlJetTransactionMode.WRITE);
			updateCursor = table.lookup(table.getPrimaryKeyIndexName(), id);
			if (!updateCursor.eof()) {
				updateCursor.update(id, serializeBulkRequest(dbBulkRequest.getRequest()),
						dbBulkRequest.getInsertTime(), dbBulkRequest.getTimesFetched());
			}
		} catch (SqlJetException | IOException e) {
			LOG.error("Updating of bulk {} has failed.", dbBulkRequest.getId(),e);
		} finally {
			silentDbCommit();
			closeCursor(updateCursor);
		}
	}

	public void dropAndRecreateTable(){
		try {
			db.dropTable(FAILED_BULKS_TABLE_NAME);
			db.createTable(CREATE_TABLE);
			table = db.getTable(FAILED_BULKS_TABLE_NAME);
			db.createIndex(createInsertTimeIndexQuery);
			LOG.info("Recreated table successfully.");
			db.commit();
		} catch (SqlJetException e) {
			LOG.error("Drop table {} has failed",FAILED_BULKS_TABLE_NAME,e);
		}
	}

	public void emptyDb() {
		LOG.info("Emptying SQLite DB...");
		ISqlJetCursor deleteCursor = null;
		try {
			db.beginTransaction(SqlJetTransactionMode.WRITE);
			deleteCursor = table.lookup(table.getPrimaryKeyIndexName());
			while (!deleteCursor.eof()) {
				deleteCursor.delete();
			}
		} catch (SqlJetException e) {
			LOG.error("Emptying the DB {} has failed",DB_NAME,e);
		} finally {
			silentDbCommit();
			closeCursor(deleteCursor);
		}
	}

	public int failedBulksAmount() {
		return fetchFailedBulks(false).size();
	}

	public void setWaitingTimeInMinutes(int waitingTimeInMinutes) {
		this.waitingTimeInMinutes = waitingTimeInMinutes;
	}

	// endregion

	//region package methods

	void persistToDisk(DbBulkRequest dbBulkRequest,long sleepTimeIfFails) throws MaximunInsertTriesException {
		int retryNum = 0;

		while (retryNum++ < maxInsertTries) {
			try {
				if (dbBulkRequest.getTimesFetched() > 0) {
					int timesFetched = dbBulkRequest.getTimesFetched();
					LOG.info("Inserting bulk request with id: {} to disk, that was fetched {} {}.", dbBulkRequest.getId(), timesFetched, timesFetched > 1 ? "times" : "time");
				} else {
					LOG.info("Inserting bulk request to disk for the first time.");
				}
				db.beginTransaction(SqlJetTransactionMode.WRITE);
				dbBulkRequest.setInsertTime(DateTime.now().toString());
				table.insert(serializeBulkRequest(dbBulkRequest.getRequest()),
						dbBulkRequest.getInsertTime(), dbBulkRequest.getTimesFetched());
				LOG.info("Bulk request was inserted successfully to SQLite.");
				break; // if arrived here then inserting succeed, no need to retry again
			} catch (Exception e) {
				LOG.error("Insertion of bulk has failed for the {}th time. Error message: {}",retryNum, e);
				silentThreadSleep(sleepTimeIfFails);
				sleepTimeIfFails*=2;
				if (retryNum == maxInsertTries){
					throw new MaximunInsertTriesException(maxInsertTries);
				}
			} finally {
				silentDbCommit();
			}
		}
	}

	List<DbBulkRequest> fetchFailedBulks(boolean deleteAfterFetch) {
		List<DbBulkRequest> dbBulkRequests = new ArrayList<>();
		ISqlJetCursor resultCursor = null;
		int fetchedCount = 0;
		DbBulkRequest dbBulkRequest;

		try {
			LOG.info("Fetching from SQLite...");
			db.beginTransaction(SqlJetTransactionMode.WRITE);
			resultCursor = getBulksInScopeIterator(); // bulks which are in db at least for the waitingTime

			while (fetchedCount < maxFetchedBulksInOneTime && !resultCursor.eof()) {
				dbBulkRequest = createDbBulkRequestFromCursor(resultCursor);
				dbBulkRequests.add(dbBulkRequest);
				if (deleteAfterFetch) {
					resultCursor.delete(); // also do next
				}else {
					resultCursor.next();
				}
				fetchedCount++;
			}
			LOG.info("Fetched successfully. Number of fetched bulks: {}.",fetchedCount);
		} catch (SqlJetException | IOException e) {
			LOG.error("Fetching has failed.",e);
		} finally {
			closeCursor(resultCursor);
			silentDbCommit();
		}
		return dbBulkRequests;
	}

	// endregion


	//region private methods

	private ISqlJetCursor getBulksInScopeIterator() throws SqlJetException {
		return table.scope(INSERT_TIME_INDEX,
				new Object[] {""}, new Object[] { DateTime.now().minusMinutes(waitingTimeInMinutes).toString()});
	}

	private DbBulkRequest createDbBulkRequestFromCursor(ISqlJetCursor resultCursor) throws IOException, SqlJetException {
		BulkRequest request = deserializeBulkRequest(resultCursor.getBlobAsArray(FAILED_TASK));
		DbBulkRequest dbBulkRequest = new DbBulkRequest(request);
		dbBulkRequest.setId((int) resultCursor.getInteger(ID));
		dbBulkRequest.setInsertTime(resultCursor.getString(INSERT_TIME));
		dbBulkRequest.setTimesFetched((int) resultCursor.getInteger(TIMES_FETCHED)+1); // increment by 1 because we call this method while fetching
		return dbBulkRequest;
	}

	private byte[] serializeBulkRequest(BulkRequest request) throws IOException {
		try (BytesStreamOutput out = new BytesStreamOutput()) {
			request.writeTo(out);
			return out.bytes().toBytesRef().bytes;
		}
	}

	private BulkRequest deserializeBulkRequest(byte[] bulkRequestBytes) throws IOException {
		StreamInput stream = null;
		try {
			BulkRequest request = new BulkRequest();
			stream = StreamInput.wrap(bulkRequestBytes);
			request.readFrom(stream);
			return request;
		}finally {
			if (stream!=null){
				stream.close();
			}
		}
	}

	private void closeCursor(ISqlJetCursor cursor) {
		try {
			if (cursor != null) {
				cursor.close();
			}
		} catch (SqlJetException e) {
			LOG.error("Closing cursor has failed",e);
		}
	}

	private void silentDbCommit(){
		try {
			db.commit();
		} catch (SqlJetException e) {
			LOG.error("Commit updates has failed",e);
		}
	}

	private void silentDbRollback()  {
		try {
			if (db!=null){
				db.rollback();
			}
		} catch (SqlJetException e) {
			LOG.error("Rollback has failed",e);
		}
	}

	private void silentCloseDb()  {
		if (db!=null){
			try {
				db.close();
			} catch (SqlJetException e) {
				LOG.error("Closing SQLite has failed",e);
			}
			db = null;
		}
	}

	private void silentThreadSleep(long sleepTime) {
		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException ex) {
			ex.printStackTrace();
		}
	}

	// endregion

	public enum SQLJetDiskHandlerParams {
		WAITING_TIME_IN_MINUTES,
		MAX_FETCHED_BULKS_IN_ONE_TIME,
		MAX_INSERT_TRIES,
		LOCATION_IN_DISK;
	}

}

