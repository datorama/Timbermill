package com.datorama.oss.timbermill;

import com.datorama.oss.timbermill.common.persistence.DbBulkRequest;
import com.datorama.oss.timbermill.common.persistence.IndexRetryManager;
import com.datorama.oss.timbermill.common.persistence.PersistenceHandler;
import com.datorama.oss.timbermill.pipe.LocalOutputPipe;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.bulk.BulkResponse;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.IOException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;

@RunWith(MockitoJUnitRunner.class)
public class TimberLogLocalPersistenceTest extends TimberLogTest {
	private static LocalOutputPipe pipe;
    private static PersistenceHandler origPersistenceHandler;
    private static IndexRetryManager retryManager;

    @BeforeClass
    public static void init() throws IOException {
        String elasticUrl = System.getenv("ELASTICSEARCH_URL");
        if (StringUtils.isEmpty(elasticUrl)){
            elasticUrl = "http://localhost:9200";
        }

		client = new ElasticsearchClientForTests(elasticUrl, null);
		pipe = buildLocalOutputPipeForTest(elasticUrl);
		origPersistenceHandler = pipe.getPersistenceHandler();
        retryManager = pipe.getEsClient().getRetryManager();
		TimberLogger.bootstrap(pipe, TEST);
	}

	@Before
	public void resetPersistenceHandlerMock() {
        PersistenceHandler persistenceHandlerSpy = Mockito.spy(origPersistenceHandler);
        retryManager.setPersistenceHandler(persistenceHandlerSpy);
	}

	@After
	public void checkTaskFailedAndPersisted() {
		Mockito.verify(retryManager.getPersistenceHandler(), atLeastOnce()).persistBulkRequest(any(), anyInt());
	}

	@AfterClass
	public static void tearDown() {
		TimberLogger.exit();
		pipe.close();
	}

	@Test
	public void testSimpleTaskIndexerJob() throws InterruptedException {
		super.testSimpleTaskIndexerJob();
	}

	@Test
	public void testSwitchCasePlugin() {
		super.testSwitchCasePlugin();
	}

	@Test
	public void testSimpleTasksFromDifferentThreadsWithWrongParentIdIndexerJob() {
		super.testSimpleTasksFromDifferentThreadsWithWrongParentIdIndexerJob();
	}

	@Test
	public void testTaskWithNullString() {
		super.testTaskWithNullString();
	}

	@Test
	public void testOverConstructor() {
		super.testOverConstructor();
	}

	@Test
	public void testOverConstructorException() {
		super.testOverConstructorException();
	}

	@Test
	public void testCorruptedInfoOnly() {
		super.testCorruptedInfoOnly();
	}

	@Test
	public void testOrphan() {
		super.testOrphan();
	}

	@Ignore
	@Test
	public void testSpotWithParent() {
		super.testSpotWithParent();
	}

	@Ignore
	@Test
	public void testSimpleTasksFromDifferentThreadsIndexerJob() {
		super.testSimpleTasksFromDifferentThreadsIndexerJob();
	}

	@Ignore
	@Test
	public void testComplexTaskIndexerWithErrorTask() {
		super.testComplexTaskIndexerWithErrorTask();
	}

	public static LocalOutputPipe buildLocalOutputPipeForTest(String elasticUrl) throws IOException {
		LocalOutputPipe.Builder builder = new LocalOutputPipe.Builder().numberOfShards(1).numberOfReplicas(0).url(elasticUrl).deletionCronExp(null).mergingCronExp(null)
				.bulkPersistentFetchCronExp("0/5 * * * * ? *") // fetch every 5 seconds
				.pluginsJson("[{\"class\":\"SwitchCasePlugin\",\"taskMatcher\":{\"name\":\"" + EVENT + "plugin"
						+ "\"},\"searchField\":\"exception\",\"outputAttribute\":\"errorType\",\"switchCase\":[{\"match\":[\"TOO_MANY_SERVER_ROWS\"],\"output\":\"TOO_MANY_SERVER_ROWS\"},{\"match\":[\"PARAMETER_MISSING\"],\"output\":\"PARAMETER_MISSING\"},{\"match\":[\"Connections could not be acquired\",\"terminating connection due to administrator\",\"connect timed out\"],\"output\":\"DB_CONNECT\"},{\"match\":[\"did not fit in memory\",\"Insufficient resources to execute plan\",\"Query exceeded local memory limit\",\"ERROR: Plan memory limit exhausted\"],\"output\":\"DB_RESOURCES\"},{\"match\":[\"Invalid input syntax\",\"SQLSyntaxErrorException\",\"com.facebook.presto.sql.parser.ParsingException\",\"com.facebook.presto.sql.analyzer.SemanticException\",\"org.postgresql.util.PSQLException: ERROR: missing FROM-clause entry\",\"org.postgresql.util.PSQLException: ERROR: invalid input syntax\"],\"output\":\"DB_SQL_SYNTAX\"},{\"match\":[\"Execution canceled by operator\",\"InterruptedException\",\"Execution time exceeded run time cap\",\"TIME_OUT\",\"canceling statement due to user request\",\"Caused by: java.net.SocketTimeoutException: Read timed out\"],\"output\":\"DB_QUERY_TIME_OUT\"},{\"output\":\"DB_UNKNOWN\"}]}]");

		Bulker bulker = client.getBulker();
		Bulker bulkerMock = Mockito.spy(bulker);
		Answer<BulkResponse> bulkResponseAnswer = invocation -> {
			Object[] args = invocation.getArguments();
			DbBulkRequest dbBulkRequest = (DbBulkRequest) args[0];
			if (dbBulkRequest.getTimesFetched() < 1) {
				throw new RuntimeException();
			}
			// call real method
			return (BulkResponse) invocation.callRealMethod();
		};
		doAnswer(bulkResponseAnswer).when(bulkerMock).bulk(any());
		builder.bulker(bulkerMock);
		builder.deletionCronExp("").eventsPersistentFetchCronExp("");
		return builder.build();
	}

}