/**
 * Copyright © 2013 enioka. All rights reserved
 * Authors: Marc-Antoine GOUILLART (marc-antoine.gouillart@enioka.com)
 *          Pierre COPPEE (pierre.coppee@enioka.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.enioka.jqm.tools;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.Persistence;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.hibernate.internal.SessionImpl;

import com.enioka.jqm.jpamodel.DatabaseProp;
import com.enioka.jqm.jpamodel.Deliverable;
import com.enioka.jqm.jpamodel.DeploymentParameter;
import com.enioka.jqm.jpamodel.GlobalParameter;
import com.enioka.jqm.jpamodel.History;
import com.enioka.jqm.jpamodel.JobHistoryParameter;
import com.enioka.jqm.jpamodel.JobInstance;
import com.enioka.jqm.jpamodel.JobParameter;
import com.enioka.jqm.jpamodel.Message;
import com.enioka.jqm.jpamodel.MessageJi;
import com.enioka.jqm.jpamodel.Node;
import com.enioka.jqm.jpamodel.Queue;
import com.enioka.jqm.jpamodel.State;

/**
 * This is a helper class for internal use only.
 * 
 */
public final class Helpers
{
    private static final String PERSISTENCE_UNIT = "jobqueue-api-pu";
    private static Logger jqmlogger = Logger.getLogger(Helpers.class);

    // The one and only EMF in the engine.
    private static Properties props = new Properties();
    private static EntityManagerFactory emf;
    private static String driverName = null;

    private Helpers()
    {

    }

    /**
     * Get a fresh EM on the jobqueue-api-pu persistence Unit
     * 
     * @return an EntityManager
     */
    public static EntityManager getNewEm()
    {
        if (emf == null)
        {
            emf = createFactory();
        }
        return emf.createEntityManager();
    }

    /**
     * Get a fresh EM with audit parameters set (for Oracle, ignored for other databases)
     * 
     * @param module
     * @param action
     * @param clientInfo
     * @return
     */
    public static EntityManager getNewEm(String module, String action, String clientInfo)
    {
        return setAuditProperties(getNewEm(), module, action, clientInfo);
    }

    private static EntityManagerFactory createFactory()
    {
        FileInputStream fis = null;
        try
        {
            Properties p = new Properties();
            fis = new FileInputStream("conf/db.properties");
            p.load(fis);
            IOUtils.closeQuietly(fis);
            props.putAll(p);
            return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, props);
        }
        catch (FileNotFoundException e)
        {
            // No properties file means we use the test HSQLDB (file, not in-memory) as specified in the persistence.xml
            IOUtils.closeQuietly(fis);
            return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, props);
        }
        catch (IOException e)
        {
            jqmlogger.fatal("conf/db.properties file is invalid", e);
            IOUtils.closeQuietly(fis);
            throw new JqmInitError("Invalid database configuration configuration file", e);
        }
        catch (Exception e)
        {
            jqmlogger.fatal("Unable to connect with the database. Maybe your configuration file is wrong. "
                    + "Please check the password or the url in the $JQM_DIR/conf/db.properties", e);
            throw new JqmInitError("Database connection issue", e);
        }
        finally
        {
            IOUtils.closeQuietly(fis);
        }
    }

    static EntityManager setAuditProperties(EntityManager em, String module, String action, String clientInfo)
    {
        if (driverName == null)
        {
            Connection conn = em.unwrap(SessionImpl.class).connection();
            driverName = conn.getClass().toString();
        }

        if (driverName.contains("oracle"))
        {
            em.getTransaction().begin();
            em.createNativeQuery("CALL DBMS_APPLICATION_INFO.SET_MODULE('" + module + "', '" + action + "')").executeUpdate();
            em.createNativeQuery("CALL DBMS_APPLICATION_INFO.SET_CLIENT_INFO('" + clientInfo + "')").executeUpdate();
            em.getTransaction().commit();
        }
        return em;
    }

    static void allowCreateSchema()
    {
        props.put("hibernate.hbm2ddl.auto", "update");
    }

    /**
     * For internal test use only <br/>
     * <bold>WARNING</bold> This will invalidate all open EntityManagers!
     */
    static void resetEmf()
    {
        if (emf != null)
        {
            emf.close();
        }
        emf = createFactory();
    }

    /**
     * Create a text message that will be stored in the database. Must be called inside a JPA transaction.
     * 
     * @param textMessage
     * @param history
     * @param em
     * @return the JPA message created
     */
    static MessageJi createMessage(String textMessage, JobInstance jobInstance, EntityManager em)
    {
        MessageJi m = new MessageJi();
        m.setTextMessage(textMessage);
        m.setJobInstance(jobInstance);
        em.persist(m);
        return m;
    }

    /**
     * Create a Deliverable inside the database that will track a file created by a JobInstance Must be called from inside a JPA transaction
     * 
     * @param fp
     *            FilePath (relative to a root directory - cf. Node)
     * @param fn
     *            FileName
     * @param hp
     *            HashPath
     * @param ff
     *            File family (may be null). E.g.: "daily report"
     * @param jobId
     *            Job Instance ID
     * @param em
     *            the EM to use.
     * @return
     */
    static Deliverable createDeliverable(String path, String originalFileName, String fileFamily, Integer jobId, EntityManager em)
    {
        Deliverable j = new Deliverable();

        j.setFilePath(path);
        j.setRandomId(UUID.randomUUID().toString());
        j.setFileFamily(fileFamily);
        j.setJobId(jobId);
        j.setOriginalFileName(originalFileName);

        em.persist(j);
        return j;
    }

    /**
     * Retrieve the value of a single-valued parameter.
     * 
     * @param key
     * @param defaultValue
     * @param em
     * @return
     */
    static String getParameter(String key, String defaultValue, EntityManager em)
    {
        try
        {
            GlobalParameter gp = em.createQuery("SELECT n from GlobalParameter n WHERE n.key = :key", GlobalParameter.class)
                    .setParameter("key", key).getSingleResult();
            return gp.getValue();
        }
        catch (NoResultException e)
        {
            return defaultValue;
        }
    }

    static Node checkAndUpdateNodeConfiguration(String nodeName, EntityManager em)
    {
        em.getTransaction().begin();

        // Node
        Node n = null;
        try
        {
            n = em.createQuery("SELECT n FROM Node n WHERE n.name = :l", Node.class).setParameter("l", nodeName).getSingleResult();
        }
        catch (NoResultException e)
        {
            jqmlogger.info("Node " + nodeName + " does not exist in the configuration and will be created with default values");
            n = new Node();
            n.setDlRepo(System.getProperty("user.dir") + "/outputfiles/");
            n.setName(nodeName);
            n.setPort(0);
            n.setRepo(System.getProperty("user.dir") + "/jobs/");
            n.setExportRepo(System.getProperty("user.dir") + "/exports/");
            em.persist(n);
        }

        // Default queue
        Queue q = null;
        long i = (Long) em.createQuery("SELECT COUNT(qu) FROM Queue qu").getSingleResult();
        jqmlogger.info("There are " + i + " queues defined in the database");
        if (i == 0L)
        {
            q = new Queue();
            q.setDefaultQueue(true);
            q.setDescription("default queue");
            q.setTimeToLive(1024);
            q.setName("DEFAULT");
            em.persist(q);

            jqmlogger.info("A default queue was created in the configuration");
        }
        else
        {
            try
            {
                q = em.createQuery("SELECT q FROM Queue q WHERE q.defaultQueue = true", Queue.class).getSingleResult();
                jqmlogger.info("Default queue is named " + q.getName());
            }
            catch (NonUniqueResultException e)
            {
                // Faulty configuration, but why not
                q = em.createQuery("SELECT q FROM Queue q", Queue.class).getResultList().get(0);
                q.setDefaultQueue(true);
                jqmlogger.info("Queue " + q.getName() + " was modified to become the default queue as there were mutliple default queue");
            }
            catch (NoResultException e)
            {
                // Faulty configuration, but why not
                q = em.createQuery("SELECT q FROM Queue q", Queue.class).getResultList().get(0);
                q.setDefaultQueue(true);
                jqmlogger.warn("Queue  " + q.getName() + " was modified to become the default queue as there was no default queue");
            }
        }

        // GlobalParameter
        GlobalParameter gp = null;
        i = (Long) em.createQuery("SELECT COUNT(gp) FROM GlobalParameter gp WHERE gp.key = :k")
                .setParameter("k", Constants.GP_DEFAULT_CONNECTION_KEY).getSingleResult();
        if (i == 0)
        {
            gp = new GlobalParameter();

            gp.setKey("mavenRepo");
            gp.setValue("http://repo1.maven.org/maven2/");
            em.persist(gp);

            gp = new GlobalParameter();
            gp.setKey(Constants.GP_DEFAULT_CONNECTION_KEY);
            gp.setValue(Constants.GP_JQM_CONNECTION_ALIAS);
            em.persist(gp);

            gp = new GlobalParameter();
            gp.setKey("deadline");
            gp.setValue("10");
            em.persist(gp);

            gp = new GlobalParameter();
            gp.setKey("logFilePerLaunch");
            gp.setValue("true");
            em.persist(gp);

            gp = new GlobalParameter();
            gp.setKey("internalPollingPeriodMs");
            gp.setValue("10000");
            em.persist(gp);

            gp = new GlobalParameter();
            gp.setKey("aliveSignalMs");
            gp.setValue("60000");
            em.persist(gp);
        }

        // Deployment parameter
        DeploymentParameter dp = null;
        i = (Long) em.createQuery("SELECT COUNT(dp) FROM DeploymentParameter dp WHERE dp.node = :localnode").setParameter("localnode", n)
                .getSingleResult();
        if (i == 0)
        {
            dp = new DeploymentParameter();
            dp.setClassId(1);
            dp.setNbThread(5);
            dp.setNode(n);
            dp.setPollingInterval(1000);
            dp.setQueue(q);
            em.persist(dp);
            jqmlogger.info("This node will poll from the default queue with default parameters");
        }
        else
        {
            jqmlogger.info("This node is configured to take jobs from at least one queue");
        }

        // JNDI alias for the JDBC connection to the JQM database
        DatabaseProp localDb = null;
        try
        {
            localDb = (DatabaseProp) em.createQuery("SELECT dp FROM DatabaseProp dp WHERE dp.name = :alias")
                    .setParameter("alias", Constants.GP_JQM_CONNECTION_ALIAS).getSingleResult();
            jqmlogger.trace("The jdbc/jqm alias already exists and references " + localDb.getUrl());
        }
        catch (NoResultException e)
        {
            Map<String, Object> props = em.getEntityManagerFactory().getProperties();
            localDb = new DatabaseProp();
            localDb.setDriver((String) props.get("javax.persistence.jdbc.driver"));
            localDb.setName(Constants.GP_JQM_CONNECTION_ALIAS);
            localDb.setPwd((String) props.get("javax.persistence.jdbc.password"));
            localDb.setUrl((String) props.get("javax.persistence.jdbc.url"));
            localDb.setUserName((String) props.get("javax.persistence.jdbc.user"));
            em.persist(localDb);

            jqmlogger.info("A  JNDI alias named " + Constants.GP_JQM_CONNECTION_ALIAS
                    + " towards the JQM db has been created. It references: " + localDb.getUrl());
        }

        // Done
        em.getTransaction().commit();
        return n;
    }

    /**
     * Transaction is not opened nor committed here but needed.
     * 
     * @param ji
     * @param em
     * @return
     */
    static History createHistory(JobInstance job, EntityManager em, State finalState, Calendar endDate)
    {
        History h = new History();
        h.setId(job.getId());
        h.setJd(job.getJd());
        h.setSessionId(job.getSessionID());
        h.setQueue(job.getQueue());
        h.setMessages(new ArrayList<Message>());
        h.setEnqueueDate(job.getCreationDate());
        h.setEndDate(endDate);
        h.setAttributionDate(job.getAttributionDate());
        h.setExecutionDate(job.getExecutionDate());
        h.setUserName(job.getUserName());
        h.setEmail(job.getEmail());
        h.setParentJobId(job.getParentId());
        h.setApplication(job.getApplication());
        h.setModule(job.getModule());
        h.setKeyword1(job.getKeyword1());
        h.setKeyword2(job.getKeyword2());
        h.setKeyword3(job.getKeyword3());
        h.setProgress(job.getProgress());
        h.setParameters(new ArrayList<JobHistoryParameter>());
        h.setStatus(finalState);
        h.setNode(job.getNode());

        em.persist(h);

        for (JobParameter j : job.getParameters())
        {
            JobHistoryParameter jp = new JobHistoryParameter();
            jp.setKey(j.getKey());
            jp.setValue(j.getValue());
            em.persist(jp);
            h.getParameters().add(jp);
        }
        for (MessageJi p : job.getMessages())
        {
            Message m = new Message();
            m.setHistory(h);
            m.setTextMessage(p.getTextMessage());
            em.persist(m);
        }

        return h;
    }
}
