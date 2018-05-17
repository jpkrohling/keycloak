package org.keycloak.services.listeners;

import io.jaegertracing.Configuration;
import io.opentracing.Tracer;
import io.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.util.GlobalTracer;
import org.jboss.logging.Logger;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.EnumSet;
import java.util.ServiceLoader;

public class OpenTracingInitializer implements ServletContextListener {
    private static final Logger logger = Logger.getLogger(OpenTracingInitializer.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (GlobalTracer.isRegistered()) {
            // There's a tracer registered already, so, we don't need to do anything
            logger.info("An OpenTracing Tracer is already registered. Skipping.");
            return;
        }

        ServiceLoader<TracerResolver> resolvers = ServiceLoader.load(TracerResolver.class);
        logger.info("Found resolver in the classpath? " + resolvers.iterator().hasNext());

        Tracer tracer = TracerResolver.resolveTracer();
        if (null == tracer) {
            logger.info("Could not get a valid OpenTracing Tracer from the classpath. Getting a Jaeger tracer manually.");
            tracer = Configuration.fromEnv().getTracer();
        }

        logger.info(String.format("Registering %s as the OpenTracing Tracer", tracer.getClass().getName()));
        GlobalTracer.register(tracer);

        FilterRegistration.Dynamic filterRegistration = sce.getServletContext()
                .addFilter("tracingFilter", new SpanFinishingFilter(tracer));
        filterRegistration.setAsyncSupported(true);
        filterRegistration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "*");
        logger.info("Added OpenTracing filter");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {

    }
}
