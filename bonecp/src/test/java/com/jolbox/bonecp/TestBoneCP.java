/**
 *  Copyright 2010 Wallace Wadge
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.jolbox.bonecp;

import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.makeThreadSafe;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.sql.DataSource;


import junit.framework.Assert;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;

import com.jolbox.bonecp.hooks.ConnectionHook;

/**
 * @author wwadge
 *
 */
public class TestBoneCP {
	/** Class under test. */
	private BoneCP testClass;
	/** Mock handle. */
	private BoneCPConfig mockConfig;
	/** Mock handle. */
	private ConnectionPartition mockPartition;
	/** Mock handle. */
	private ScheduledExecutorService mockKeepAliveScheduler;
	/** Mock handle. */
	private ExecutorService mockConnectionsScheduler;
	/** Mock handle. */
	private LinkedBlockingQueue<ConnectionHandle> mockConnectionHandles;
	/** Mock handle. */
	private ConnectionHandle mockConnection;
	/** Mock handle. */
	private Lock mockLock;
	/** Mock handle. */
	private Logger mockLogger;
	/** Mock handle. */
	private DatabaseMetaData mockDatabaseMetadata;
	/** Mock handle. */
	private MockResultSet mockResultSet;
	/** Fake database driver. */
	private MockJDBCDriver driver;

	/**
	 * Reset the mocks.
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws SQLException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 * @throws CloneNotSupportedException 
	 */
	@Before
	@SuppressWarnings({ "unchecked", "deprecation" })
	public void before() throws IllegalArgumentException, IllegalAccessException, SQLException, SecurityException, NoSuchFieldException, CloneNotSupportedException{
		driver = new MockJDBCDriver(new MockJDBCAnswer() {

			public Connection answer() throws SQLException {
				return new MockConnection();
			}
		});
		mockConfig = EasyMock.createNiceMock(BoneCPConfig.class);
		expect(mockConfig.clone()).andReturn(mockConfig).anyTimes();
		expect(mockConfig.getPartitionCount()).andReturn(2).anyTimes();
		expect(mockConfig.getMaxConnectionsPerPartition()).andReturn(1).anyTimes();
		expect(mockConfig.getMinConnectionsPerPartition()).andReturn(1).anyTimes();
		expect(mockConfig.getIdleConnectionTestPeriodInMinutes()).andReturn(0L).anyTimes();
		expect(mockConfig.getIdleMaxAgeInMinutes()).andReturn(1000L).anyTimes();
		expect(mockConfig.getUsername()).andReturn(CommonTestUtils.username).anyTimes();
		expect(mockConfig.getPassword()).andReturn(CommonTestUtils.password).anyTimes();
		expect(mockConfig.getJdbcUrl()).andReturn(CommonTestUtils.url).anyTimes();
		expect(mockConfig.getReleaseHelperThreads()).andReturn(1).once().andReturn(0).anyTimes();
		expect(mockConfig.getStatementReleaseHelperThreads()).andReturn(1).once().andReturn(0).anyTimes();
		expect(mockConfig.getInitSQL()).andReturn(CommonTestUtils.TEST_QUERY).anyTimes();
		expect(mockConfig.isCloseConnectionWatch()).andReturn(true).anyTimes();
		expect(mockConfig.isLogStatementsEnabled()).andReturn(true).anyTimes();
		expect(mockConfig.getConnectionTimeoutInMs()).andReturn(Long.MAX_VALUE).anyTimes();
		expect(mockConfig.getServiceOrder()).andReturn("LIFO").anyTimes();

		expect(mockConfig.getAcquireRetryDelayInMs()).andReturn(1000L).anyTimes();
		expect(mockConfig.getPoolName()).andReturn("poolName").anyTimes();
		expect(mockConfig.getPoolAvailabilityThreshold()).andReturn(20).anyTimes();
		
		replay(mockConfig);

		// once for no {statement, connection} release threads, once with release threads....
		testClass = new BoneCP(mockConfig);
		testClass = new BoneCP(mockConfig);

		Field field = testClass.getClass().getDeclaredField("partitions");
		field.setAccessible(true);
		ConnectionPartition[] partitions = (ConnectionPartition[]) field.get(testClass);


		// if all ok 
		assertEquals(2, partitions.length);
		// switch to our mock version now
		mockPartition = EasyMock.createNiceMock(ConnectionPartition.class);
		Array.set(field.get(testClass), 0, mockPartition);
		Array.set(field.get(testClass), 1, mockPartition);

		mockKeepAliveScheduler = EasyMock.createNiceMock(ScheduledExecutorService.class); 
		field = testClass.getClass().getDeclaredField("keepAliveScheduler");
		field.setAccessible(true);
		field.set(testClass, mockKeepAliveScheduler);

		field = testClass.getClass().getDeclaredField("connectionsScheduler");
		field.setAccessible(true);
		mockConnectionsScheduler = EasyMock.createNiceMock(ExecutorService.class);
		field.set(testClass, mockConnectionsScheduler);

		mockConnectionHandles = EasyMock.createNiceMock(LinkedBlockingQueue.class);
		mockConnection = EasyMock.createNiceMock(ConnectionHandle.class);
		mockLock = EasyMock.createNiceMock(Lock.class);
		mockLogger = TestUtils.mockLogger(testClass.getClass());
		makeThreadSafe(mockLogger, true);
		mockDatabaseMetadata = EasyMock.createNiceMock(DatabaseMetaData.class);
		mockResultSet = EasyMock.createNiceMock(MockResultSet.class);

		mockLogger.error((String)anyObject(), anyObject());
		expectLastCall().anyTimes();

		reset(mockConfig, mockKeepAliveScheduler, mockConnectionsScheduler, mockPartition, 
				mockConnectionHandles, mockConnection, mockLock);
	}

	/**
	 * @throws SQLException
	 */
	@After
	public void teardown() throws SQLException{
		driver.disable();
	}
	
	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#shutdown()}.
	 * @throws IllegalAccessException 
	 * @throws NoSuchFieldException 
	 * @throws IllegalArgumentException 
	 * @throws SecurityException 
	 */
	@Test
	@Ignore
	public void testShutdown() throws SecurityException, IllegalArgumentException, NoSuchFieldException, IllegalAccessException {
		testShutdownClose(true);
	}
	

	/** Tests obtaining internal connection.
	 * @throws SQLException
	 * @throws ClassNotFoundException
	 *
	@Test
	public void testObtainInternalConnection() throws SQLException, ClassNotFoundException{
		expect(this.mockPool.getConfig()).andReturn(this.config).anyTimes();

		this.testClass.url = "jdbc:mock:driver";
		this.config.setAcquireRetryDelayInMs(1);
		CustomHook testHook = new CustomHook();
		this.config.setConnectionHook(testHook);
		// make it fail the first time and succeed the second time
		expect(this.mockPool.getDbIsDown()).andReturn(new AtomicBoolean()).anyTimes();
		expect(this.mockPool.obtainRawInternalConnection()).andThrow(new SQLException()).once().andReturn(this.mockConnection).once();
		replay(this.mockPool);
		this.testClass.obtainInternalConnection(this.mockPool, "jdbc:mock");
		// get counts on our hooks
		
		assertEquals(1, testHook.fail);
		assertEquals(1, testHook.acquire);
		
		// Test 2: Same thing but without the hooks
		reset(this.mockPool);
		expect(this.mockPool.getDbIsDown()).andReturn(new AtomicBoolean()).anyTimes();
		expect(this.mockPool.getConfig()).andReturn(this.config).anyTimes();
		expect(this.mockPool.obtainRawInternalConnection()).andThrow(new SQLException()).once().andReturn(this.mockConnection).once();
		count=1;
		this.config.setConnectionHook(null);
		replay(this.mockPool);
		assertEquals(this.mockConnection, this.testClass.obtainInternalConnection(this.mockPool, "jdbc:mock"));
		
		// Test 3: Keep failing
		reset(this.mockPool);
		expect(this.mockPool.getConfig()).andReturn(this.config).anyTimes();
		expect(this.mockPool.obtainRawInternalConnection()).andThrow(new SQLException()).anyTimes();
		replay(this.mockPool);
		count=99;
		this.config.setAcquireRetryAttempts(2);
		try{
			this.testClass.obtainInternalConnection(this.mockPool, "jdbc:mock");
			fail("Should have thrown an exception");
		} catch (SQLException e){
			// expected behaviour
		}
		 
		//	Test 4: Get signalled to interrupt fail delay
		count=99;
		this.config.setAcquireRetryAttempts(2);
		this.config.setAcquireRetryDelayInMs(7000);
		final Thread currentThread = Thread.currentThread();

		try{
			new Thread(new Runnable() {
				
//				@Override
				public void run() {
					while (!currentThread.getState().equals(State.TIMED_WAITING)){
						try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					currentThread.interrupt();
				}
			}).start();
			this.testClass.obtainInternalConnection(this.mockPool, "jdbc:mock");
			fail("Should have thrown an exception");
		} catch (SQLException e){
			// expected behaviour
		}
		this.config.setAcquireRetryDelayInMs(10);
		
		
	}
	*/


	/**
	 * Tests shutdown/close method.
	 * @param doShutdown call shutdown or call close
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 */
	private void testShutdownClose(boolean doShutdown) throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
		expect(mockConfig.isDeregisterDriverOnClose()).andReturn(false).anyTimes();

		expect(mockKeepAliveScheduler.shutdownNow()).andReturn(null).once();
		expect(mockConnectionsScheduler.shutdownNow()).andReturn(null).once();

		expect(mockConnectionHandles.poll()).andReturn(null).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
	

		ExecutorService mockReleaseHelper = EasyMock.createNiceMock(ExecutorService.class);


		replay(mockConfig, mockConnectionsScheduler,  mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockReleaseHelper);
		
		if (doShutdown){
			testClass.shutdown();
		} else {
			testClass.close();
		}
		verify(mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles);
	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#close()}.
	 * @throws IllegalAccessException 
	 * @throws NoSuchFieldException 
	 * @throws IllegalArgumentException 
	 * @throws SecurityException 
	 */ 
	@Test
	@Ignore
	public void testClose() throws SecurityException, IllegalArgumentException, NoSuchFieldException, IllegalAccessException {
		testClass.poolShuttingDown=false;
		testShutdownClose(false);
	}

	/**
	 * Test method.
	 * @throws SQLException 
	 */
	@Test
	@Ignore
	public void testTerminateAllConnections() throws SQLException {
		expect(mockConnectionHandles.poll()).andReturn(mockConnection).times(2).andReturn(null).once();
		mockConnection.internalClose();
		expectLastCall().once().andThrow(new SQLException()).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();
		replay(mockRealConnection, mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockConnection);

		// test.
		testClass.connectionStrategy.terminateAllConnections();
		verify(mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockConnection);
	}


	/**
	 * Test method for.
	 * @throws SQLException 
	 */
	@Test
	@Ignore
	public void testTerminateAllConnections2() throws SQLException {
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);

		// same test but to cover the finally section
		reset(mockRealConnection, mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockConnection);
		expect(mockConnectionHandles.poll()).andReturn(mockConnection).anyTimes();
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();
		mockConnection.internalClose();
		expectLastCall().once().andThrow(new RuntimeException()).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		replay(mockRealConnection, mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockConnection);

		// test.
		try{
			testClass.connectionStrategy.terminateAllConnections();
			fail("Should throw exception");
		} catch (RuntimeException e){
			// do nothing
		}
		verify(mockConnectionsScheduler, mockKeepAliveScheduler, mockPartition, mockConnectionHandles, mockConnection);
	}
	/**
	 * Mostly for coverage.
	 */
	@Test
	public void testPostDestroyConnection(){
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);

		reset(mockConnection);
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		mockPartition.updateCreatedConnections(-1);
		expectLastCall().once();
		mockPartition.setUnableToCreateMoreTransactions(false);
		expectLastCall().once();
		ConnectionHook mockConnectionHook = EasyMock.createNiceMock(ConnectionHook.class);
		expect(mockConnection.getConnectionHook()).andReturn(mockConnectionHook).anyTimes();
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();

		mockConnectionHook.onDestroy(mockConnection);
		expectLastCall().once();
		replay(mockRealConnection, mockConnectionHook, mockConnection);
		testClass.postDestroyConnection(mockConnection);
		verify(mockConnectionHook, mockConnection);
	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getConnection()}.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnection() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {
		// Test 1: Get connection - normal state

		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		expect(mockConnectionHandles.poll()).andReturn(mockConnection).once();
		mockConnection.renewConnection();
		expectLastCall().once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		assertEquals(mockConnection, testClass.getConnection());
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getConnection()}.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnectionWithTimeout() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {

		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.poll()).andReturn(null).anyTimes();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		try{
			testClass.getConnection();
			fail("Should have thrown an exception");
		} catch (SQLException e){
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}


	/**
	 * Test method.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnectionViaDataSourceBean() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {
		DataSource mockDataSource = EasyMock.createNiceMock(DataSource.class);
		expect(mockDataSource.getConnection()).andReturn(mockConnection).once();
		expect(mockConfig.getJdbcUrl()).andReturn("").once().andReturn("jdbc:mock:foo").once();
		expect(mockConfig.getUsername()).andReturn(null).anyTimes();
		expect(mockConfig.getDatasourceBean()).andReturn(mockDataSource).once().andReturn(null).once();


	
		replay(mockConfig, mockDataSource);
		testClass.obtainRawInternalConnection();
		// 2nd path
		testClass.obtainRawInternalConnection();
		verify(mockConfig, mockDataSource);
	}

	
	/**
	 * Test method.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnectionWithExternalAuth() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {
		boolean oldValue = testClass.externalAuth;
		testClass.externalAuth = true;
		expect(mockConfig.getDriverProperties()).andReturn(null).once();
		expect(mockConfig.getDatasourceBean()).andReturn(null).once();
		expect(mockConfig.getJdbcUrl()).andReturn(CommonTestUtils.url).once();
		replay(mockConfig);
		assertNotNull(testClass.obtainRawInternalConnection());
		verify(mockConfig);
		testClass.externalAuth = oldValue;
	}


	/**
	 * Test method.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnectionViaDriverProperties() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {
		//		DataSource mockDataSource = EasyMock.createNiceMock(DataSource.class);
		//		expect(mockDataSource.getConnection()).andReturn(mockConnection).once();
		expect(mockConfig.getDriverProperties()).andReturn(new Properties()).once();
		expect(mockConfig.getDatasourceBean()).andReturn(null).once();
		expect(mockConfig.getJdbcUrl()).andReturn(CommonTestUtils.url).once();
		replay(mockConfig);
		testClass.obtainRawInternalConnection();
		verify(mockConfig);
	}

	/**
	 * Test method.
	 * @throws SQLException 
	 * @throws InterruptedException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 */
	@Test
	public void testGetConnectionViaDataSourceBeanWithPass() throws SQLException, InterruptedException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException {
		DataSource mockDataSource = EasyMock.createNiceMock(DataSource.class);
		expect(mockDataSource.getConnection((String)anyObject(), (String)anyObject())).andReturn(mockConnection).once();
		expect(mockConfig.getJdbcUrl()).andReturn("").once();
		expect(mockConfig.getUsername()).andReturn("username").once();
		expect(mockConfig.getPassword()).andReturn(null).once();
		expect(mockConfig.getDatasourceBean()).andReturn(mockDataSource).once();
		replay(mockConfig, mockDataSource);
		testClass.obtainRawInternalConnection();
		verify(mockConfig, mockDataSource);
	}
	/**
	 * Attempting to fetch a connection from a pool that is marked as being shut down should throw an exception
	 */
	@Test
	public void testGetConnectionOnShutdownPool() {
		// Test #8: 
		testClass.poolShuttingDown = true;
		try{
			testClass.getConnection();
			fail("Should have thrown an exception");
		} catch (SQLException e){
			// do nothing
		}
	}


	/** Like test 6, except we fake an unchecked exception to make sure our locks are released.
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 */
	@Test
	public void testGetConnectionUncheckedExceptionTriggeredWhileWaiting()
	throws NoSuchFieldException, IllegalAccessException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(false).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getMaxConnections()).andReturn(0).anyTimes(); // cause a division by zero error
		replay(mockPartition, mockConnectionHandles, mockConnection, mockLock);
		try{
			testClass.getConnection();
			fail("Should have thrown an exception");
		} catch (Throwable t){
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection, mockLock);
	}


	/** If we hit our limit, we should signal for more connections to be created on the fly
	 * @throws SQLException
	 */
	@Test
	public void testGetConnectionLimitsHit() throws SQLException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockConfig.getPoolAvailabilityThreshold()).andReturn(0).anyTimes();
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(false).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getMaxConnections()).andReturn(10).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		BlockingQueue<Object> bq = new ArrayBlockingQueue<Object>(1);
		bq.add(new Object());
		expect(mockPartition.getPoolWatchThreadSignalQueue()).andReturn(bq);


		//		mockPartition.almostFullSignal();
		//		expectLastCall().once();

		expect(mockConnectionHandles.poll()).andReturn(mockConnection).once();
		mockConnection.renewConnection();
		expectLastCall().once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		testClass.getConnection();
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}


	/** Connection queues are starved of free connections. Should block and wait on one without spin-locking.
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws InterruptedException
	 * @throws SQLException
	 */
	@Test
	public void testGetConnectionConnectionQueueStarved()
	throws NoSuchFieldException, IllegalAccessException,
	InterruptedException, SQLException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andReturn(mockConnection).once();

		mockConnection.renewConnection();
		expectLastCall().once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		assertEquals(mockConnection, testClass.getConnection());
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}


	/** Simulate an interrupted exception elsewhere.
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws InterruptedException
	 */
	@Test
	public void testGetConnectionSimulateInterruptedException2()
	throws NoSuchFieldException, IllegalAccessException,
	InterruptedException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.poll()).andReturn(null).once();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andThrow(new InterruptedException()).once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		try{
			testClass.getConnection();
			fail("Should have throw an SQL Exception");
		} catch (SQLException e){
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}

	/** Simulate an interrupted exception elsewhere.
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws InterruptedException
	 */
	@Test
	public void testGetConnectionSimulateInterruptedExceptionWithNullReturn()
	throws NoSuchFieldException, IllegalAccessException,
	InterruptedException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		testClass.nullOnConnectionTimeout = true;
		expect(mockConnectionHandles.poll()).andReturn(null).once();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andThrow(new InterruptedException()).once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		try{
			assertNull(testClass.getConnection());
		} catch (SQLException e){
			fail("Should not have throw an SQL Exception");
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}
	

	/**	Test #3: Like test #2 but simulate an interrupted exception
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws InterruptedException
	 */
	@Test
	public void testGetConnectionSimulateInterruptedException()
	throws NoSuchFieldException, IllegalAccessException,
	InterruptedException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.getMaxConnections()).andReturn(100).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andThrow(new InterruptedException()).once();
		BlockingQueue<Object> bq = new ArrayBlockingQueue<Object>(1);
		bq.add(new Object());
		expect(mockPartition.getPoolWatchThreadSignalQueue()).andReturn(bq);
		replay(mockPartition, mockConnectionHandles, mockConnection);
		try{ 
			testClass.getConnection();
			fail("Should have throw an SQL Exception");
		} catch (SQLException e){
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}

	/**	Test #3: Like test #2 but simulate an interrupted exception
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws InterruptedException
	 */
	@Test
	public void testGetConnectionWithNullReturn()
	throws NoSuchFieldException, IllegalAccessException,
	InterruptedException {
		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.getMaxConnections()).andReturn(100).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andReturn(null).once();
		BlockingQueue<Object> bq = new ArrayBlockingQueue<Object>(1);
		bq.add(new Object());
		testClass.nullOnConnectionTimeout = true;
		expect(mockPartition.getPoolWatchThreadSignalQueue()).andReturn(bq);
		replay(mockPartition, mockConnectionHandles, mockConnection);
		try{ 
			assertNull(testClass.getConnection());
		} catch (SQLException e){
			fail("Should have throw an SQL Exception");
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}

	
	/** Get connection, not finding any available block to wait for one
	 * @throws InterruptedException
	 * @throws SQLException
	 */
	@Test
	public void testGetConnectionBlockOnUnavailable()
	throws InterruptedException, SQLException {

		reset(mockPartition, mockConnectionHandles, mockConnection);
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		expect(mockConnectionHandles.poll()).andReturn(null).once();
		expect(mockConnectionHandles.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)).andReturn(mockConnection).once();

		mockConnection.renewConnection();
		expectLastCall().once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		assertEquals(mockConnection, testClass.getConnection());
		verify(mockPartition, mockConnectionHandles, mockConnection);
	}

	/** Test obtaining a connection asynchronously.
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	@Test
	public void testGetAsyncConnection() throws InterruptedException, ExecutionException{
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		expect(mockConnectionHandles.poll()).andReturn(mockConnection).once();
		mockConnection.renewConnection();
		expectLastCall().once();

		replay(mockPartition, mockConnectionHandles, mockConnection);
		assertEquals(mockConnection, testClass.getAsyncConnection().get());
		verify(mockPartition, mockConnectionHandles, mockConnection);
 
	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#releaseConnection(java.sql.Connection)}.
	 * @throws SQLException 
	 * @throws IllegalAccessException 
	 * @throws IllegalArgumentException 
	 * @throws NoSuchFieldException 
	 * @throws SecurityException 
	 * @throws InterruptedException 
	 */
	@Test
	public void testReleaseConnection() throws SQLException, IllegalArgumentException, IllegalAccessException, SecurityException, NoSuchFieldException, InterruptedException {
		// Test #1: Test releasing connections directly
		expect(mockConnection.isPossiblyBroken()).andReturn(false).once();
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();

		//		expect(mockConnectionHandles.offer(mockConnection)).andReturn(true).once();

		// should reset last connection use
		mockConnection.setConnectionLastUsedInMs(anyLong());
		expectLastCall().once();
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();
	
		replay(mockRealConnection, mockConnection, mockPartition, mockConnectionHandles);
		testClass.releaseConnection(mockConnection);
		verify(mockConnection, mockPartition, mockConnectionHandles);

		reset(mockConnection, mockPartition, mockConnectionHandles);
	}


	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#internalReleaseConnection(ConnectionHandle)}.
	 * @throws InterruptedException 
	 * @throws SQLException 
	 */
	@Test
	public void testInternalReleaseConnection() throws InterruptedException, SQLException {
		// Test #1: Test normal case where connection is considered to be not broken
		// should reset last connection use
		mockConnection.setConnectionLastUsedInMs(anyLong());
		expectLastCall().once();
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();
	
		//		expect(mockConnectionHandles.offer(mockConnection)).andReturn(false).anyTimes();
		expect(mockConnectionHandles.offer(mockConnection)).andReturn(true).once();

		replay(mockRealConnection, mockConnection,mockPartition, mockConnectionHandles);
		testClass.internalReleaseConnection(mockConnection);
		verify(mockConnection,mockPartition, mockConnectionHandles);
	}


	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#internalReleaseConnection(ConnectionHandle)}.
	 * @throws InterruptedException 
	 * @throws SQLException 
	 */
	@Test
	public void testInternalReleaseConnectionWhereConnectionIsBroken() throws InterruptedException, SQLException {
		// Test case where connection is broken
		mockConnection.logicallyClosed = new AtomicBoolean(false);
		reset(mockConnection,mockPartition, mockConnectionHandles);
		expect(mockConnection.isPossiblyBroken()).andReturn(true).once();
		expect(mockConfig.getConnectionTestStatement()).andReturn(null).once();
		// make it fail to return false
		expect(mockConnection.getMetaData()).andThrow(new SQLException()).once();

		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		// we're about to destroy this connection, so we can create new ones.
		mockPartition.setUnableToCreateMoreTransactions(false);
		expectLastCall().once();

		// break out from the next method, we're not interested in it
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();

		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();

		replay(mockPartition, mockConnection);
		testClass.internalReleaseConnection(mockConnection);
		verify(mockPartition, mockConnection);
	}

    /**
     * Test method for {@link com.jolbox.bonecp.BoneCP#internalReleaseConnection(ConnectionHandle)}.
     *
     * @throws InterruptedException
     * @throws SQLException
     */
    @Test
    public void testInternalReleaseConnectionWhereConnectionIsExpired() throws InterruptedException, SQLException {
        // Test case where connection is expired
        reset(mockConnection, mockPartition, mockConnectionHandles);

        Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
        expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();

        // return a partition
        expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
        // break out from this method, we're not interested in it
        expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(true).once();

        expect(mockConnection.isExpired()).andReturn(true).anyTimes();
        mockConnection.internalClose();
        expectLastCall();

        replay(mockPartition, mockConnection, mockRealConnection);
        testClass.internalReleaseConnection(mockConnection);
        verify(mockPartition, mockConnection, mockRealConnection);
    }

    /**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#putConnectionBackInPartition(com.jolbox.bonecp.ConnectionHandle)}.
	 * @throws InterruptedException 
	 * @throws SQLException 
	 */
	@Test
	public void testPutConnectionBackInPartition() throws InterruptedException, SQLException {
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		Connection mockRealConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockConnection.getInternalConnection()).andReturn(mockRealConnection).anyTimes();
	
		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		expect(mockConnectionHandles.offer(mockConnection)).andReturn(true).once();
		replay(mockRealConnection, mockPartition, mockConnectionHandles, mockConnection);
		testClass.putConnectionBackInPartition(mockConnection);
		// FIXME
		//		assertEquals(2, ai.get());
		verify(mockPartition, mockConnectionHandles);

	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#putConnectionBackInPartition(com.jolbox.bonecp.ConnectionHandle)}.
	 * @throws InterruptedException 
	 * @throws SQLException 
	 */
	@Test
	public void testPutConnectionBackInPartitionWithResetConnectionOnClose() throws InterruptedException, SQLException {
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();

		expect(mockConnection.getOriginatingPartition()).andReturn(mockPartition).anyTimes();
		expect(mockConnectionHandles.offer(mockConnection)).andReturn(true).once();
		expect(mockConnection.isTxResolved()).andReturn(false).once();
		Connection mockInternalConnection = EasyMock.createNiceMock(Connection.class);
		expect(mockInternalConnection.getAutoCommit()).andReturn(false).once();
		expect(mockConnection.getInternalConnection()).andReturn(mockInternalConnection).anyTimes();
		mockInternalConnection.rollback();
		mockInternalConnection.setAutoCommit(true);
		replay(mockInternalConnection, mockPartition, mockConnectionHandles, mockConnection);
		testClass.resetConnectionOnClose = true;
		testClass.putConnectionBackInPartition(mockConnection);
		verify(mockPartition, mockConnectionHandles);

	}

	
	/**
	 * Test method for com.jolbox.bonecp.BoneCP isConnectionHandleAlive.
	 * @throws SQLException 
	 */
	@Test
	public void testIsConnectionHandleAlive() throws SQLException {
		// Test 1: Normal case (+ without connection test statement)
		expect(mockConfig.getConnectionTestStatement()).andReturn(null).once();
		mockConnection.logicallyClosed=new AtomicBoolean();
		expect(mockConnection.getMetaData()).andReturn(mockDatabaseMetadata).once();
		expect(mockDatabaseMetadata.getTables((String)anyObject(), (String)anyObject(), (String)anyObject(), (String[])anyObject())).andReturn(mockResultSet).once();
		mockResultSet.close();
		expectLastCall().once();

		replay(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);
		assertTrue(testClass.isConnectionHandleAlive(mockConnection));
		verify(mockConfig, mockConnection, mockResultSet,mockDatabaseMetadata);
	}
	
	/**
	 * Test method for com.jolbox.bonecp.BoneCP isConnectionHandleAlive.
	 * @throws SQLException 
	 */
	@Test
	public void testIsConnectionHandleAliveTriggerException() throws SQLException {
	
		// Test 2: Same test as testIsConnectionHandleAlive but triggers an exception
		reset(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);
		expect(mockConfig.getConnectionTestStatement()).andReturn(null).once();
		expect(mockConnection.getMetaData()).andThrow(new SQLException()).once();
		mockConnection.logicallyClosed = new AtomicBoolean(false);

		replay(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);
		assertFalse(testClass.isConnectionHandleAlive(mockConnection));
		verify(mockConfig, mockConnection, mockResultSet,mockDatabaseMetadata);

	}
	
	/**
	 * Test method for com.jolbox.bonecp.BoneCP isConnectionHandleAlive.
	 * @throws SQLException 
	 */
	@Test
	public void testIsConnectionHandleAliveNormalCaseWithConnectionTestStatement() throws SQLException {
	
		// Test 3: Normal case (+ with connection test statement)
		reset(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);

		Statement mockStatement = EasyMock.createNiceMock(Statement.class);
		expect(mockConfig.getConnectionTestStatement()).andReturn("whatever").once();
		expect(mockConnection.createStatement()).andReturn(mockStatement).once();
		expect(mockStatement.execute((String)anyObject())).andReturn(true).once();
		//		mockResultSet.close();
		//		expectLastCall().once();

		mockConnection.logicallyClosed = new AtomicBoolean(true);
		replay(mockConfig, mockConnection, mockDatabaseMetadata,mockStatement);
		assertTrue(testClass.isConnectionHandleAlive(mockConnection));
		verify(mockConfig, mockConnection,mockDatabaseMetadata, mockStatement);
	}
	/**
	 * Test method for com.jolbox.bonecp.BoneCP isConnectionHandleAlive.
	 * @throws SQLException 
	 */
	@Test
	public void testIsConnectionHandleAliveNormalCaseWithConnectionTestTriggerException() throws SQLException {
		Statement mockStatement = EasyMock.createNiceMock(Statement.class);
		// Test 4: Same like test testIsConnectionHandleAliveNormalCaseWithConnectionTestStatement but triggers exception
		reset(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);

		expect(mockConfig.getConnectionTestStatement()).andReturn("whatever").once();
		expect(mockConnection.createStatement()).andReturn(mockStatement).once();
		expect(mockStatement.execute((String)anyObject())).andThrow(new RuntimeException()).once();
		mockStatement.close();
		expectLastCall().once();

		replay(mockConfig, mockConnection, mockDatabaseMetadata,mockStatement, mockResultSet);
		//		assertFalse(testClass.isConnectionHandleAlive(mockConnection));
		try{
			mockConnection.logicallyClosed = new AtomicBoolean(true);// (code coverage)
			testClass.isConnectionHandleAlive(mockConnection);
			fail("Should have thrown an exception");
		} catch (RuntimeException e){
			// do nothing 
		}
		verify(mockConfig, mockConnection, mockResultSet,mockDatabaseMetadata, mockStatement);
	}
	
	/**
	 * Test method for com.jolbox.bonecp.BoneCP isConnectionHandleAlive.
	 * @throws SQLException 
	 */
	@Test
	public void testIsConnectionHandleAliveNormalCaseWithConnectionTestTriggerExceptionInFinally() throws SQLException {
		Statement mockStatement = EasyMock.createNiceMock(Statement.class);
		
		// Test 5: Same like test testIsConnectionHandleAliveNormalCaseWithConnectionTestTriggerException
		// but triggers exception in finally block
		reset(mockConfig, mockConnection, mockDatabaseMetadata, mockResultSet);

		expect(mockConfig.getConnectionTestStatement()).andReturn("whatever").once();
		expect(mockConnection.createStatement()).andReturn(mockStatement).once();
		expect(mockStatement.execute((String)anyObject())).andThrow(new RuntimeException()).once();
		mockStatement.close();
		expectLastCall().andThrow(new SQLException()).once();

		mockConnection.logicallyClosed = new AtomicBoolean(false);
		replay(mockConfig, mockConnection, mockDatabaseMetadata,mockStatement, mockResultSet);
		try{
			testClass.isConnectionHandleAlive(mockConnection);
			fail("Should have thrown an exception");
		} catch (RuntimeException e){
			// do nothing
		}
		verify(mockConfig, mockConnection, mockResultSet,mockDatabaseMetadata, mockStatement);

	}

	/**
	 * Test method for maybeSignalForMoreConnections(com.jolbox.bonecp.ConnectionPartition)}.
	 * @throws SecurityException 
	 * @throws NoSuchMethodException 
	 * @throws IllegalArgumentException 
	 * @throws IllegalAccessException 
	 * @throws InvocationTargetException 
	 */
	@Test
	public void testMaybeSignalForMoreConnections() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException{
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(false).once();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		//		expect(mockConnectionHandles.size()).andReturn(1).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(1).anyTimes();
		expect(mockPartition.getMaxConnections()).andReturn(10).anyTimes();
		BlockingQueue<Object> bq = new ArrayBlockingQueue<Object>(1);
		expect(mockPartition.getPoolWatchThreadSignalQueue()).andReturn(bq).anyTimes();
		//		mockPartition.lockAlmostFullLock();
		//		expectLastCall().once();
		//		mockPartition.almostFullSignal();
		//		expectLastCall().once();
		//		mockPartition.unlockAlmostFullLock();
		//		expectLastCall().once();
		replay(mockPartition, mockConnectionHandles);
		Method method = testClass.getClass().getDeclaredMethod("maybeSignalForMoreConnections", ConnectionPartition.class);
		method.setAccessible(true);
		method.invoke(testClass, new Object[]{mockPartition});
		verify(mockPartition, mockConnectionHandles);
	}
	
	/**
	 * Test method for maybeSignalForMoreConnections(com.jolbox.bonecp.ConnectionPartition)}.
	 * @throws SecurityException 
	 * @throws NoSuchMethodException 
	 * @throws IllegalArgumentException 
	 * @throws IllegalAccessException 
	 * @throws InvocationTargetException 
	 */
	@Test
	public void testMaybeSignalForMoreConnectionsWithException() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException{
		BlockingQueue<Object> bq = new ArrayBlockingQueue<Object>(1);
		Method method = testClass.getClass().getDeclaredMethod("maybeSignalForMoreConnections", ConnectionPartition.class);
		method.setAccessible(true);
		
		// Test 2, same test but fake an exception
		reset(mockPartition, mockConnectionHandles);
		expect(mockPartition.getPoolWatchThreadSignalQueue()).andReturn(bq).anyTimes();
		expect(mockPartition.isUnableToCreateMoreTransactions()).andReturn(false).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockConnectionHandles.size()).andReturn(1).anyTimes();
		expect(mockPartition.getMaxConnections()).andReturn(10).anyTimes();
		//		mockPartition.lockAlmostFullLock();
		//		expectLastCall().once();
		//		mockPartition.almostFullSignal();
		//		expectLastCall().andThrow(new RuntimeException()).once();
		//		mockPartition.unlockAlmostFullLock();
		//		expectLastCall().once(); 
		replay(mockPartition, mockConnectionHandles);
		try{
			method.invoke(testClass, new Object[]{mockPartition});
			fail("Should have thrown an exception");
		} catch (Throwable t){
			// do nothing
		}
		verify(mockPartition, mockConnectionHandles);

	}
	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getTotalLeased()}.
	 */
	@Test
	public void testGetTotalLeased() {
		expect(mockPartition.getCreatedConnections()).andReturn(5).anyTimes();
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(3).anyTimes();
		replay(mockPartition, mockConnectionHandles);
		assertEquals(4, testClass.getTotalLeased());
		verify(mockPartition, mockConnectionHandles);

	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getTotalFree()}.
	 */
	@Test
	public void testGetTotalFree() {
		expect(mockPartition.getFreeConnections()).andReturn(mockConnectionHandles).anyTimes();
		expect(mockPartition.getAvailableConnections()).andReturn(3).anyTimes();

		// expect(mockConnectionHandles.size()).andReturn(3).anyTimes();
		replay(mockPartition, mockConnectionHandles);
		assertEquals(6, testClass.getTotalFree());
		verify(mockPartition, mockConnectionHandles);

	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getTotalCreatedConnections()}.
	 */
	@Test
	public void testGetTotalCreatedConnections() {
		expect(mockPartition.getCreatedConnections()).andReturn(5).anyTimes();
		replay(mockPartition);
		assertEquals(10, testClass.getTotalCreatedConnections());
		verify(mockPartition);

	}

	/**
	 * Test method for {@link com.jolbox.bonecp.BoneCP#getConfig()}.
	 */
	@Test
	public void testGetConfig() {
		assertEquals(mockConfig, testClass.getConfig());
	}

	/**
	 * Coverage.
	 * @throws SQLException 
	 */
	@Test
	public void testCoverage() throws SQLException{
		BoneCPConfig config = EasyMock.createNiceMock(BoneCPConfig.class);
		expect(config.getJdbcUrl()).andReturn(CommonTestUtils.url).anyTimes();
		expect(config.getMinConnectionsPerPartition()).andReturn(2).anyTimes();
		expect(config.getMaxConnectionsPerPartition()).andReturn(20).anyTimes();
		expect(config.getPartitionCount()).andReturn(1).anyTimes();
		expect(config.getServiceOrder()).andReturn("LIFO").anyTimes();
		expect(config.getMaxConnectionAgeInSeconds()).andReturn(100000L).anyTimes();
		replay(config);

	}

	/** JMX setup test
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws NotCompliantMBeanException
	 * @throws InstanceNotFoundException 
	 */
	@Test
	public void testJMXRegister() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException, InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException, InstanceNotFoundException{
		MBeanServer mockMbs = EasyMock.createNiceMock(MBeanServer.class);
		Field field = testClass.getClass().getDeclaredField("mbs");
		field.setAccessible(true);
		field.set(testClass, mockMbs);
		expect(mockConfig.getPoolName()).andReturn(null).anyTimes(); 
		ObjectInstance mockInstance = EasyMock.createNiceMock(ObjectInstance.class);
		expect(mockMbs.isRegistered((ObjectName)anyObject())).andReturn(false).anyTimes();
		expect(mockMbs.registerMBean(anyObject(), (ObjectName)anyObject())).andReturn(mockInstance).once().andThrow(new InstanceAlreadyExistsException()).once();
		replay(mockMbs, mockInstance, mockConfig);
		testClass.registerUnregisterJMX(true);
		verify(mockMbs);
	}
	
	@Test
	public void testJMXUnregisterNoName() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException, InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException, InstanceNotFoundException{
		MBeanServer mockMbs = EasyMock.createNiceMock(MBeanServer.class);
		Field field = testClass.getClass().getDeclaredField("mbs");
		field.setAccessible(true);
		field.set(testClass, mockMbs);
		expect(mockConfig.getPoolName()).andReturn(null).anyTimes(); 
		ObjectInstance mockInstance = EasyMock.createNiceMock(ObjectInstance.class);
	
		reset(mockMbs, mockInstance, mockConfig);
		expect(mockMbs.isRegistered((ObjectName)anyObject())).andReturn(true).anyTimes();
		mockMbs.unregisterMBean((ObjectName)anyObject());
		replay(mockMbs, mockInstance, mockConfig);
		testClass.registerUnregisterJMX(false);
		verify(mockMbs);
	}

	/** Test for different pool names.
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws NotCompliantMBeanException
	 */
	@Test
	public void testJMXRegisterWithName() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException, InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException{
		MBeanServer mockMbs = EasyMock.createNiceMock(MBeanServer.class);
		Field field = testClass.getClass().getDeclaredField("mbs");
		field.setAccessible(true);
		field.set(testClass, mockMbs);
		expect(mockConfig.getPoolName()).andReturn("poolName").anyTimes();
		ObjectInstance mockInstance = EasyMock.createNiceMock(ObjectInstance.class);
		expect(mockMbs.isRegistered((ObjectName)anyObject())).andReturn(false).anyTimes();
		expect(mockMbs.registerMBean(anyObject(), (ObjectName)anyObject())).andReturn(mockInstance).once().andThrow(new InstanceAlreadyExistsException()).once();
		replay(mockMbs, mockInstance, mockConfig);
		testClass.registerUnregisterJMX(true);
		verify(mockMbs);
	}

	/** Test for different pool names.
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InstanceAlreadyExistsException
	 * @throws MBeanRegistrationException
	 * @throws NotCompliantMBeanException
	 * @throws InstanceNotFoundException 
	 */
	@Test
	public void testJMXUnRegisterWithName() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException, InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException, InstanceNotFoundException{
		MBeanServer mockMbs = EasyMock.createNiceMock(MBeanServer.class);
		Field field = testClass.getClass().getDeclaredField("mbs");
		field.setAccessible(true);
		field.set(testClass, mockMbs);
	
		field = testClass.getClass().getDeclaredField("config");
		field.setAccessible(true);
		field.set(testClass, mockConfig);
	
		expect(mockConfig.getPoolName()).andReturn("poolName").anyTimes();
		ObjectInstance mockInstance = EasyMock.createNiceMock(ObjectInstance.class);
		expect(mockMbs.isRegistered((ObjectName)anyObject())).andReturn(true).anyTimes();
		mockMbs.unregisterMBean((ObjectName)anyObject());
		expectLastCall().times(2).andThrow(new InstanceNotFoundException()).once();
		replay(mockMbs, mockInstance, mockConfig);
		testClass.registerUnregisterJMX(false);
		testClass.registerUnregisterJMX(false); // should trigger an exception

		verify(mockMbs);
	}

	/**
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 */
	@Test
	public void testCaptureException() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException{
		Method method = testClass.getClass().getDeclaredMethod("captureStackTrace",  String.class);
		method.setAccessible(true);
		try{
			method.invoke(testClass);
			fail("Should throw an exception");
		} catch (Exception e){
			// do nothing
		}
	}

	/** Tests for watch connection.
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws NoSuchFieldException
	 */
	@Test
	public void testWatchConnection() throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException, NoSuchFieldException{
		Field field = testClass.getClass().getDeclaredField("closeConnectionExecutor");
		field.setAccessible(true);

		ExecutorService mockExecutor = EasyMock.createNiceMock(ExecutorService.class);
		field.set(testClass, mockExecutor);


		Method method = testClass.getClass().getDeclaredMethod("watchConnection", ConnectionHandle.class);
		method.setAccessible(true);
		expect(mockExecutor.submit((CloseThreadMonitor) anyObject())).andReturn(null).once();
		replay(mockExecutor);
		method.invoke(testClass, mockConnection);
		verify(mockExecutor);

		// Test #2: Code coverage
		method.invoke(testClass, new Object[]{null});
	}
	
	/**
	 * 
	 */
	@Test
	public void testUnregisterDriver() {
		expect(mockConfig.isDeregisterDriverOnClose()).andReturn(true).anyTimes();
		expect(mockConfig.getJdbcUrl()).andReturn("jdbc:mock").anyTimes();

		replay(mockConfig);
		try {
			Assert.assertNotNull(DriverManager.getDriver("jdbc:mock"));
		} catch (SQLException e1) {
			fail("SQLException thrown");
		}
		testClass.unregisterDriver();
		
		testClass.unregisterDriver(); // should log a message (nothing to unregister)
	}


}