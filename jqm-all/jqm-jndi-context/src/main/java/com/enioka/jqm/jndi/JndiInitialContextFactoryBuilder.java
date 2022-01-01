package com.enioka.jqm.jndi;

import java.util.Hashtable;

import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.naming.spi.InitialContextFactoryBuilder;

import org.osgi.service.component.annotations.Component;

@Component(service = InitialContextFactoryBuilder.class, immediate = true)
public class JndiInitialContextFactoryBuilder implements InitialContextFactoryBuilder
{
    private InitialContextFactory initialContextFactory;

    public JndiInitialContextFactoryBuilder() throws NamingException
    {
        initialContextFactory = new JndiContext();
    }

    @Override
    public InitialContextFactory createInitialContextFactory(Hashtable<?, ?> environment) throws NamingException
    {
        return initialContextFactory;
    }
}
