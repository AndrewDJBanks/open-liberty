/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.jdbc.heritage.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.annotation.Resource;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.sql.DataSource;
import javax.transaction.UserTransaction;

import org.junit.Test;

import componenttest.app.FATServlet;

@SuppressWarnings("serial")
@WebServlet(urlPatterns = "/JDBCHeritageTestServlet")
public class JDBCHeritageTestServlet extends FATServlet {
    @Resource
    private DataSource defaultDataSource;

    @Resource
    private UserTransaction tx;

    @Override
    public void destroy() {
        super.destroy();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    /**
     * Utility method that accesses the internal field WSJdbcPreparedStatement.pstmtImpl.
     * This is useful for determining if we got the same statement from the statement cache.
     *
     * @param pstmt prepared statement wrapper (WSJdbcPreparedStatement).
     * @return prepared statement implementation.
     */
    private static PreparedStatement pstmtImpl(PreparedStatement pstmt) throws PrivilegedActionException {
        return AccessController.doPrivileged((PrivilegedExceptionAction<PreparedStatement>) () -> {
            for (Class<?> c = pstmt.getClass(); c != null; c = c.getSuperclass())
                try {
                    Field pstmtImpl = c.getDeclaredField("pstmtImpl");
                    pstmtImpl.setAccessible(true);
                    return (PreparedStatement) pstmtImpl.get(pstmt);
                } catch (NoSuchFieldException x) {
                    // ignore, try the super class
                }
            throw new NoSuchFieldException("Unable to find pstmtImpl on prepared statement wrapper. If the field has been renamed, you will need to update this test.");
        });
    }

    /**
     * Verifies that doConnectionSetup was performed on a new connection.
     * Verifies that doConnectionSetupPerGetConnection is invoked for each first connection handle.
     * Verifies that doConnectionCleanupPerCloseConnection is invoked upon closing or dissociating the last connection handle.
     */
    @Test
    public void testConnectionSetup() throws Exception {
        try (Connection con = defaultDataSource.getConnection("testConnectionSetupUser", "PASSWORD")) {

            // Read the value that was set by doConnectionSetup
            Statement stmt = con.createStatement();
            ResultSet result = stmt.executeQuery("VALUES SYSCS_UTIL.SYSCS_GET_RUNTIMESTATISTICS()");
            assertTrue(result.next());
            assertNotNull(result.getString(1));
            stmt.close();

            // Query the doConnectionSetupPerGetConnection count, expecting 1
            result = con.prepareCall("CALL TEST.GET_SETUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();

            // Query the doConnectionCleanupPerCloseConnection count, expecting 0
            result = con.prepareCall("CALL TEST.GET_CLEANUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();
        }

        Connection con;
        ResultSet result;

        tx.begin();
        try {
            con = defaultDataSource.getConnection("testConnectionSetupUser", "PASSWORD");

            result = con.prepareCall("VALUES(2)").executeQuery();
            assertTrue(result.next());
            result.getStatement().close();

            // Query the doConnectionSetupPerGetConnection count, expecting 2
            result = con.prepareCall("CALL TEST.GET_SETUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();

            // Query the doConnectionCleanupPerCloseConnection count, expecting 1
            result = con.prepareCall("CALL TEST.GET_CLEANUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();

            Connection con2 = defaultDataSource.getConnection("testConnectionSetupUser", "PASSWORD");

            // Query the doConnectionSetupPerGetConnection count, still expecting 2
            result = con.prepareCall("CALL TEST.GET_SETUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();

            // Query the doConnectionCleanupPerCloseConnection count, still expecting 1
            result = con.prepareCall("CALL TEST.GET_CLEANUP_COUNT()").executeQuery();
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
            result.getStatement().close();
        } finally {
            tx.commit();
        }

        // Query the doConnectionSetupPerGetConnection count
        result = con.prepareCall("CALL TEST.GET_SETUP_COUNT()").executeQuery();
        assertTrue(result.next());
        // assertEquals(3, result.getInt(1)); // legacy behavior does not invoke doConnectionSetupPerGetConnection on reassociate
        result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
        result.getStatement().close();

        // Query the doConnectionCleanupPerCloseConnection count, expecting 2
        result = con.prepareCall("CALL TEST.GET_CLEANUP_COUNT()").executeQuery();
        assertTrue(result.next());
        assertEquals(2, result.getInt(1));
        result.getStatement().setPoolable(false); // statement caching interferes with test replacing sql
        result.getStatement().close();

        con.close();
    }

    /**
     * Confirm that a dataSource that is configured with heritageSettings can be injected
     * and has the transaction isolation level that is assigned as default by the DataStoreHelper.
     */
    @Test
    public void testDefaultIsolationLevel() throws Exception {
        assertNotNull(defaultDataSource);
        try (Connection con = defaultDataSource.getConnection()) {
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());
        }
    }

    /**
     * Confirm that doesStatementCacheIsoLevel causes prepared statements to be cached based on
     * the isolation level that was present on the connection at the time the statement was
     * created.
     */
    @Test
    public void testStatementCachingBasedOnIsolationLevel() throws Exception {
        try (Connection con = defaultDataSource.getConnection()) {
            String sql = "VALUES('testStatementCachingBasedOnIsolationLevel')";
            PreparedStatement pstmt;

            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            pstmt = con.prepareStatement(sql);
            PreparedStatement pstmtRC = pstmtImpl(pstmt);
            pstmt.close();

            con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            pstmt = con.prepareStatement(sql);
            PreparedStatement pstmtRR = pstmtImpl(pstmt);
            pstmt.close();

            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            pstmt = con.prepareStatement(sql);
            PreparedStatement pstmtRC2 = pstmtImpl(pstmt);
            pstmt.close();

            assertNotSame(pstmtRC, pstmtRR);
            assertSame(pstmtRC, pstmtRC2);
        }
    }
}