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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Logger;

import com.enioka.jqm.jpamodel.JobInstance;
import com.enioka.jqm.jpamodel.JobParameter;

@SuppressWarnings({ "unchecked", "rawtypes" })
class JarClassLoader extends URLClassLoader
{
    private static Logger jqmlogger = Logger.getLogger(JarClassLoader.class);

    private static URL[] addUrls(URL url, URL[] libs)
    {
        URL[] urls = new URL[libs.length + 1];
        urls[0] = url;
        System.arraycopy(libs, 0, urls, 1, libs.length);
        return urls;
    }

    JarClassLoader(URL url, URL[] libs)
    {
        super(addUrls(url, libs), null);
    }

    private boolean isLegacyPayload(Class c)
    {
        Class clazz = c;
        while (!clazz.equals(Object.class))
        {
            if (clazz.getName().equals(Constants.API_OLD_IMPL))
            {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    void launchJar(JobInstance job) throws JqmEngineException
    {
        // 1st: load the class
        String classQualifiedName = job.getJd().getJavaClassName();
        jqmlogger.debug("Will now load class: " + classQualifiedName);

        Class c = null;
        try
        {
            c = loadClass(classQualifiedName);
        }
        catch (Throwable e)
        {
            throw new JqmEngineException("could not load class " + classQualifiedName, e);
        }
        jqmlogger.trace("Class " + classQualifiedName + " was correctly loaded");

        // 2nd: what type of payload is this?
        if (Runnable.class.isAssignableFrom(c))
        {
            jqmlogger.trace("This payload is of type: Runnable");
            launchRunnable(c, job);
            return;
        }
        else if (isLegacyPayload(c))
        {
            jqmlogger.trace("This payload is of type: explicit API implementation");
            launchApiPayload(c, job);
            return;
        }
        else
        {
            // Might have a main
            Method start = null;
            try
            {
                start = c.getMethod("main");
            }
            catch (NoSuchMethodException e)
            {
                // Nothing - let's try with arguments
            }
            if (start == null)
            {
                try
                {
                    start = c.getMethod("main", String[].class);
                }
                catch (NoSuchMethodException e)
                {
                    // Nothing
                }
            }
            if (start != null)
            {
                jqmlogger.trace("This payload is of type: static main");
                launchMain(c, job);
                return;
            }
        }

        throw new JqmEngineException("This type of class cannot be launched by JQM. Please consult the documentation for more details.");
    }

    private void launchApiPayload(Class c, JobInstance job) throws JqmEngineException
    {
        Object o = null;
        try
        {
            o = c.newInstance();
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Cannot create an instance of class " + c.getCanonicalName()
                    + ". Does it have an argumentless constructor?", e);
        }

        // Injection
        inject(o.getClass(), o, job);

        try
        {
            // Start method that we will have to call
            Method start = c.getMethod("start");
            start.invoke(o);
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof RuntimeException)
            {
                // it may be a Kill order, or whatever exception...
                throw (RuntimeException) e.getCause();
            }
            else
            {
                throw new JqmEngineException("Payload has failed", e);
            }
        }
        catch (NoSuchMethodException e)
        {
            throw new JqmEngineException("Payload " + c.getCanonicalName() + " is incorrect - missing fields and methods.", e);
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Payload launch failed for " + c.getCanonicalName() + ".", e);
        }
    }

    private void launchRunnable(Class<Runnable> c, JobInstance job) throws JqmEngineException
    {
        Runnable o = null;
        try
        {
            o = c.newInstance();
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Could not instanciate runnable payload. Does it have a nullary constructor?", e);
        }

        // Injection stuff (if needed)
        inject(o.getClass(), o, job);

        // Go
        o.run();
    }

    private void launchMain(Class c, JobInstance job) throws JqmEngineException
    {
        boolean withArgs = false;
        Method start = null;
        try
        {
            start = c.getMethod("main");
        }
        catch (NoSuchMethodException e)
        {
            // Nothing - let's try with arguments
        }
        try
        {
            start = c.getMethod("main", String[].class);
            withArgs = true;
        }
        catch (NoSuchMethodException e)
        {
            throw new JqmEngineException("The main type payload does not have a valid main static method");
        }

        if (!Modifier.isStatic(start.getModifiers()))
        {
            throw new JqmEngineException("The main type payload has a main function but it is not static");
        }

        // Injection
        inject(c, null, job);

        // Parameters
        String[] params = new String[job.getParameters().size()];
        Collections.sort(job.getParameters(), new Comparator<JobParameter>()
        {
            public int compare(JobParameter o1, JobParameter o2)
            {
                return o1.getKey().compareTo(o2.getKey());
            };
        });
        int i = 0;
        for (JobParameter p : job.getParameters())
        {
            params[i] = p.getValue();
            i++;
        }

        // Start
        try
        {
            if (withArgs)
            {
                start.invoke(null, (Object) params);
            }
            else
            {
                start.invoke(null);
            }
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof RuntimeException)
            {
                // it may be a Kill order, or whatever exception...
                throw (RuntimeException) e.getCause();
            }
            else
            {
                throw new JqmEngineException("Payload has failed", e);
            }
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Payload launch failed for " + c.getCanonicalName() + ".", e);
        }
    }

    private void inject(Class c, Object o, JobInstance job) throws JqmEngineException
    {
        List<Field> ff = new ArrayList<Field>();
        Class clazz = c;
        while (!clazz.equals(Object.class))
        {
            ff.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        boolean inject = false;
        for (Field f : ff)
        {
            if (Constants.API_INTERFACE.equals(f.getType().getName()))
            {
                jqmlogger.trace("The object should be injected at least on field " + f.getName());
                inject = true;
                break;
            }
        }
        if (!inject)
        {
            jqmlogger.trace("This object has no fields available for injection. No injection will take place.");
            return;
        }

        JobManagerHandler h = new JobManagerHandler(job);
        Class injInt = null;
        Object proxy = null;
        try
        {
            injInt = loadClass("com.enioka.jqm.api.JobManager");
            proxy = Proxy.newProxyInstance(this, new Class[] { injInt }, h);
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Could not load JQM internal interface", e);
        }
        try
        {
            for (Field f : ff)
            {
                if (f.getType().equals(injInt))
                {
                    jqmlogger.trace("Injecting interface JQM into field " + f.getName());
                    boolean acc = f.isAccessible();
                    f.setAccessible(true);
                    f.set(o, proxy);
                    f.setAccessible(acc);
                }
            }
        }
        catch (Exception e)
        {
            throw new JqmEngineException("Could not inject JQM interface into target payload", e);
        }
    }
}
