/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.xml;

import java.util.Map;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Collections;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import net.jcip.annotations.ThreadSafe;
import org.apache.sis.util.logging.Logging;
import org.apache.sis.internal.jaxb.AdapterReplacement;


/**
 * Creates and configures {@link Marshaller} or {@link Unmarshaller} objects for use with SIS.
 * Users fetch (un)marshallers by calls to the {@link #acquireMarshaller()} or
 * {@link #acquireUnmarshaller()} methods, and can restitute the (un)marshaller to the pool
 * after usage like below:
 *
 * {@preformat java
 *     Marshaller marshaller = pool.acquireMarshaller();
 *     marshaller.marchall(...);
 *     pool.release(marshaller);
 * }
 *
 * {@section Configuring (un)marshallers}
 * The (un)marshallers created by this class can optionally by configured with the SIS-specific
 * properties defined in the {@link XML} class, in addition to JAXB standard properties.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.3 (derived from geotk-3.00)
 * @version 0.3
 * @module
 *
 * @see XML
 *
 * @todo Need a timeout for disposing marshallers that have been unused for a while.
 */
@ThreadSafe
public class MarshallerPool {
    /**
     * Maximal amount of marshallers and unmarshallers to keep.
     */
    private static final int CAPACITY = 16;

    /**
     * The key to be used in the map given to the constructors for specifying the root namespace.
     * An example of value for this key is {@code "http://www.isotc211.org/2005/gmd"}.
     */
    public static final String ROOT_NAMESPACE_KEY = "org.apache.sis.xml.rootNamespace";

    /**
     * The JAXB context to use for creating marshaller and unmarshaller.
     */
    private final JAXBContext context;

    /**
     * {@code true} if the JAXB implementation is the one bundled in JDK 6,
     * or {@code false} if this is an external implementation like a JAR put
     * in the endorsed directory.
     */
    private final boolean internal;

    /**
     * The mapper between namespaces and prefix.
     */
    private final Object mapper;

    /**
     * The pool of marshaller. This pool is initially empty
     * and will be filled with elements as needed.
     */
    private final Deque<Marshaller> marshallers = new LinkedList<>();

    /**
     * The pool of unmarshaller. This pool is initially empty
     * and will be filled with elements as needed.
     */
    private final Deque<Unmarshaller> unmarshallers = new LinkedList<>();

    /**
     * Creates a new factory for the given class to be bound, with a default empty namespace.
     *
     * @param  classesToBeBound The classes to be bound, for example {@code DefaultMetadata.class}.
     * @throws JAXBException    If the JAXB context can not be created.
     */
    public MarshallerPool(final Class<?>... classesToBeBound) throws JAXBException {
        this(Collections.<String,String>emptyMap(), classesToBeBound);
    }

    /**
     * Creates a new factory for the given class to be bound. The keys in the {@code properties} map
     * shall be one or many of the constants defined in this class like {@link #ROOT_NAMESPACE_KEY}.
     *
     * @param  properties       The set of properties to be given to the pool.
     * @param  classesToBeBound The classes to be bound, for example {@code DefaultMetadata.class}.
     * @throws JAXBException    If the JAXB context can not be created.
     */
    public MarshallerPool(final Map<String,String> properties, final Class<?>... classesToBeBound) throws JAXBException {
        this(properties, JAXBContext.newInstance(classesToBeBound));
    }

    /**
     * Creates a new factory for the given packages, with a default empty namespace.
     * The separator character for the packages is the colon.
     *
     * @param  packages         The packages in which JAXB will search for annotated classes to be bound,
     *                          for example {@code "org.apache.sis.metadata.iso:org.apache.sis.metadata.iso.citation"}.
     * @throws JAXBException    If the JAXB context can not be created.
     */
    public MarshallerPool(final String packages) throws JAXBException {
        this(Collections.<String,String>emptyMap(), packages);
    }

    /**
     * Creates a new factory for the given packages. The separator character for the packages is the
     * colon. The keys in the {@code properties} map shall be one or many of the constants defined
     * in this class like {@link #ROOT_NAMESPACE_KEY}.
     *
     * @param  properties    The set of properties to be given to the pool.
     * @param  packages      The packages in which JAXB will search for annotated classes to be bound,
     *                       for example {@code "org.apache.sis.metadata.iso:org.apache.sis.metadata.iso.citation"}.
     * @throws JAXBException If the JAXB context can not be created.
     */
    public MarshallerPool(final Map<String,String> properties, final String packages) throws JAXBException {
        this(properties, JAXBContext.newInstance(packages));
    }

    /**
     * Creates a new factory for the given packages.
     *
     * @param  properties    The set of properties to be given to the pool.
     * @param  context       The JAXB context.
     * @throws JAXBException If the OGC namespace prefix mapper can not be created.
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // Generic array creation
    private MarshallerPool(final Map<String,String> properties, final JAXBContext context) throws JAXBException {
        this.context = context;
        String rootNamespace = properties.get(ROOT_NAMESPACE_KEY);
        if (rootNamespace == null) {
            rootNamespace = "";
        }
        /*
         * Detects if we are using the endorsed JAXB implementation (i.e. the one provided in
         * separated JAR files).  If not, we will assume that we are using the implementation
         * bundled in JDK 6. We use the JAXB context package name as a criterion.
         *
         *   JAXB endorsed JAR uses    "com.sun.xml.bind"
         *   JAXB bundled in JDK uses  "com.sun.xml.internal.bind"
         */
        internal = !context.getClass().getName().startsWith("com.sun.xml.bind");
        String type = "org.apache.sis.xml.OGCNamespacePrefixMapper_Endorsed";
        if (internal) {
            type = type.substring(0, type.lastIndexOf('_'));
        }
        /*
         * Instantiates the OGCNamespacePrefixMapper appropriate for the implementation
         * we just detected.
         */
        try {
            mapper = Class.forName(type).getConstructor(String.class).newInstance(rootNamespace);
        } catch (ReflectiveOperationException | NoClassDefFoundError exception) {
            // The NoClassDefFoundError is because of our trick using "geotk-provided".
            throw new JAXBException("Unsupported JAXB implementation.", exception);
        }
    }

    /**
     * Returns the marshaller or unmarshaller to use from the given queue.
     * If the queue is empty, returns {@code null}.
     */
    private static <T> T acquire(final Deque<T> queue) {
        synchronized (queue) {
            return queue.pollLast();
        }
    }

    /**
     * Marks the given marshaller or unmarshaller available for further reuse.
     */
    private static <T> void release(final Deque<T> queue, final T marshaller) {
        try {
            ((Pooled) marshaller).reset();
        } catch (JAXBException exception) {
            // Not expected to happen because the we are supposed
            // to reset the properties to their initial values.
            Logging.unexpectedException(MarshallerPool.class, "release", exception);
            return;
        }
        synchronized (queue) {
            queue.addLast(marshaller);
            while (queue.size() > CAPACITY) {
                // Remove the least recently used marshallers.
                queue.removeFirst();
            }
        }
    }

    /**
     * Returns a JAXB marshaller from the pool. If there is no marshaller currently available
     * in the pool, then this method will {@linkplain #createMarshaller create} a new one.
     *
     * <p>This method shall be used as below:</p>
     *
     * {@preformat java
     *     Marshaller marshaller = pool.acquireMarshaller();
     *     marshaller.marchall(...);
     *     pool.release(marshaller);
     * }
     *
     * Note that this is not strictly required to release the marshaller in a {@code finally}
     * block. Actually it is safer to let the garbage collector disposes the marshaller if an
     * error occurred while marshalling the object.
     *
     * @return A marshaller configured for formatting OGC/ISO XML.
     * @throws JAXBException If an error occurred while creating and configuring a marshaller.
     */
    public Marshaller acquireMarshaller() throws JAXBException {
        Marshaller marshaller = acquire(marshallers);
        if (marshaller == null) {
            marshaller = new PooledMarshaller(createMarshaller(), internal);
        }
        return marshaller;
    }

    /**
     * Returns a JAXB unmarshaller from the pool. If there is no unmarshaller currently available
     * in the pool, then this method will {@linkplain #createUnmarshaller create} a new one.
     *
     * <p>This method shall be used as below:</p>
     *
     * {@preformat java
     *     Unmarshaller unmarshaller = pool.acquireUnmarshaller();
     *     Unmarshaller.unmarchall(...);
     *     pool.release(unmarshaller);
     * }
     *
     * Note that this is not strictly required to release the unmarshaller in a {@code finally}
     * block. Actually it is safer to let the garbage collector disposes the unmarshaller if an
     * error occurred while unmarshalling the object.
     *
     * @return A unmarshaller configured for parsing OGC/ISO XML.
     * @throws JAXBException If an error occurred while creating and configuring the unmarshaller.
     */
    public Unmarshaller acquireUnmarshaller() throws JAXBException {
        Unmarshaller unmarshaller = acquire(unmarshallers);
        if (unmarshaller == null) {
            unmarshaller = new PooledUnmarshaller(createUnmarshaller(), internal);
        }
        return unmarshaller;
    }

    /**
     * Declares a marshaller as available for reuse. The caller should not use
     * anymore the marshaller after this method call.
     *
     * @param marshaller The marshaller to return to the pool.
     */
    public void release(final Marshaller marshaller) {
        release(marshallers, marshaller);
    }

    /**
     * Declares a unmarshaller as available for reuse. The caller should not use
     * anymore the unmarshaller after this method call.
     *
     * @param unmarshaller The unmarshaller to return to the pool.
     */
    public void release(final Unmarshaller unmarshaller) {
        release(unmarshallers, unmarshaller);
    }

    /**
     * Creates an configure a new JAXB marshaller.
     * This method is invoked only when no existing marshaller is available in the pool.
     * Subclasses can override this method if they need to change the marshaller configuration.
     *
     * @return A new marshaller configured for formatting OGC/ISO XML.
     * @throws JAXBException If an error occurred while creating and configuring the marshaller.
     */
    protected Marshaller createMarshaller() throws JAXBException {
        final String mapperKey = internal ?
            "com.sun.xml.internal.bind.namespacePrefixMapper" :
            "com.sun.xml.bind.namespacePrefixMapper";
        final Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        marshaller.setProperty(mapperKey, mapper);
        synchronized (AdapterReplacement.PROVIDER) {
            for (final AdapterReplacement adapter : AdapterReplacement.PROVIDER) {
                adapter.register(marshaller);
            }
        }
        return marshaller;
    }

    /**
     * Creates an configure a new JAXB unmarshaller.
     * This method is invoked only when no existing unmarshaller is available in the pool.
     * Subclasses can override this method if they need to change the unmarshaller configuration.
     *
     * @return A new unmarshaller configured for parsing OGC/ISO XML.
     * @throws JAXBException If an error occurred while creating and configuring the unmarshaller.
     */
    protected Unmarshaller createUnmarshaller() throws JAXBException {
        final Unmarshaller unmarshaller = context.createUnmarshaller();
        synchronized (AdapterReplacement.PROVIDER) {
            for (final AdapterReplacement adapter : AdapterReplacement.PROVIDER) {
                adapter.register(unmarshaller);
            }
        }
        return unmarshaller;
    }
}
