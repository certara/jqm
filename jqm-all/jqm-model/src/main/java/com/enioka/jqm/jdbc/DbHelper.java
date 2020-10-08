package com.enioka.jqm.jdbc;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DbHelper
{
    private static Logger jqmlogger = LoggerFactory.getLogger(DbHelper.class);

    /**
     * Close utility method.
     *
     * @param ps
     *               statement to close.
     */
    static void closeQuietly(Closeable ps)
    {
        if (ps != null)
        {
            try
            {
                ps.close();
            }
            catch (Exception e)
            {
                jqmlogger.warn("Could not close a closeable - possible leak", e);
            }
        }
    }

    /**
     * Close utility method for statement & resultset.
     *
     * @param rs
     *               statement to close (through its RS).
     */
    static void closeQuietly(ResultSet rs)
    {
        if (rs != null)
        {
            try
            {
                rs.close();
                rs.getStatement().close();
            }
            catch (Exception e)
            {
                // Should go with the connection anyway.
                // jqmlogger.warn("Could not close a DB result set - possible pool leak", e);
            }
        }
    }

    /**
     * Close utility method.
     *
     * @param ps
     *               statement to close.
     */
    static void closeQuietly(Connection connection)
    {
        if (connection != null)
        {
            try
            {
                connection.close();
            }
            catch (Exception e)
            {
                jqmlogger.warn("Could not close a DB connection - possible pool leak", e);
            }
        }
    }

    /**
     * Close utility method.
     *
     * @param ps
     *               statement to close.
     */
    static void closeQuietly(Statement st)
    {
        if (st != null)
        {
            try
            {
                st.close();
            }
            catch (Exception e)
            {
                // Should go with the connection anyway.
                // jqmlogger.warn("Could not close a DB statement - possible pool leak", e);
            }
        }
    }
}
