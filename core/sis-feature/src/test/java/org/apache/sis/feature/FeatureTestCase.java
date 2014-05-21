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
package org.apache.sis.feature;

import org.opengis.metadata.quality.Element;
import org.opengis.metadata.quality.Result;
import org.opengis.metadata.quality.ConformanceResult;
import org.apache.sis.util.iso.SimpleInternationalString;
import org.apache.sis.test.DependsOnMethod;
import org.apache.sis.test.TestCase;
import org.junit.Test;

import static org.apache.sis.test.Assert.*;
import static java.util.Collections.singletonMap;


/**
 * Base class of {@link DenseFeatureTest} and {@link SparseFeatureTest}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.5
 * @version 0.5
 * @module
 */
abstract strictfp class FeatureTestCase extends TestCase {
    /**
     * The feature being tested.
     */
    private AbstractFeature feature;

    /**
     * {@code true} if {@link #getAttributeValue(String)} should invoke {@link AbstractFeature#getProperty(String)},
     * or {@code false} for invoking directly {@link AbstractFeature#getPropertyValue(String)}.
     */
    private boolean getValuesFromProperty;

    /**
     * For sub-class constructors only.
     */
    FeatureTestCase() {
    }

    /**
     * Creates a feature for twin towns.
     */
    static AbstractFeature twinTown(final boolean isSparse) {
        final DefaultAssociationRole twinTown = DefaultAssociationRoleTest.twinTown();
        final DefaultFeatureType     city     = twinTown.getValueType();
        final DefaultFeatureType     type     = new DefaultFeatureType(
                singletonMap(DefaultFeatureType.NAME_KEY, "Twin town"), false,
                new DefaultFeatureType[] {city}, twinTown);

        final AbstractFeature leMans = isSparse ? new SparseFeature(type) : new DenseFeature(type);
        leMans.setPropertyValue("city", "Le Mans");
        leMans.setPropertyValue("population", 143240); // In 2011.

        final AbstractFeature paderborn = isSparse ? new SparseFeature(type) : new DenseFeature(type);
        paderborn.setPropertyValue("city", "Paderborn");
        paderborn.setPropertyValue("population", 143174); // December 31th, 2011
        paderborn.setPropertyValue("twin town", leMans);
        return paderborn;
    }

    /**
     * Creates a new feature for the given type.
     */
    abstract AbstractFeature createFeature(final DefaultFeatureType type);

    /**
     * Returns the attribute value of the current {@link #feature} for the given name.
     */
    private Object getAttributeValue(final String name) {
        final Object value = feature.getPropertyValue(name);
        if (getValuesFromProperty) {
            final Property property = (Property) feature.getProperty(name);
            assertInstanceOf(name, DefaultAttribute.class, property);

            // The AttributeType shall be the same than the one provided by FeatureType for the given name.
            assertSame(name, feature.getType().getProperty(name), ((DefaultAttribute<?>) property).getType());

            // Attribute value shall be the same than the one provided by FeatureType convenience method.
            assertSame(name, feature.getPropertyValue(name), ((DefaultAttribute<?>) property).getValue());

            // Invoking getProperty(name) twice shall return the same Property instance.
            assertSame(name, property, feature.getProperty(name));
        }
        return value;
    }

    /**
     * Sets the attribute of the given name to the given value.
     * First, this method verifies that the previous value is equals to the given one.
     * Then, this method set the attribute to the given value and check if the result.
     *
     * @param name     The name of the attribute to set.
     * @param oldValue The expected old value (may be {@code null}).
     * @param newValue The new value to set.
     */
    private void setAttributeValue(final String name, final Object oldValue, final Object newValue) {
        assertEquals(name, oldValue, getAttributeValue(name));
        feature.setPropertyValue(name, newValue);
        assertEquals(name, newValue, getAttributeValue(name));
    }

    /**
     * Tests the {@link AbstractFeature#getPropertyValue(String)} method on a simple feature without super-types.
     * This method also tests that attempts to set a value of the wrong type throw an exception and leave the
     * previous value unchanged, that the feature is cloneable and that serialization works.
     */
    @Test
    public void testSimpleValues() {
        feature = createFeature(DefaultFeatureTypeTest.city());
        setAttributeValue("city", "Utopia", "Atlantide");
        try {
            feature.setPropertyValue("city", 2000);
        } catch (ClassCastException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("city"));
            assertTrue(message, message.contains("Integer"));
        }
        assertEquals("Property shall not have been modified.", "Atlantide", getAttributeValue("city"));
        setAttributeValue("population", null, 1000);
        assertValid();
        testSerialization();
        try {
            testClone("population", 1000, 1500);
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Tests the {@link AbstractFeature#getProperty(String)} method on a simple feature without super-types.
     * This method also tests that attempts to set a value of the wrong type throw an exception and leave the
     * previous value unchanged.
     */
    @Test
    @DependsOnMethod("testSimpleValues")
    public void testSimpleProperties() {
        getValuesFromProperty = true;
        testSimpleValues();
    }

    /**
     * Tests {@link AbstractFeature#getProperty(String)} and {@link AbstractFeature#getPropertyValue(String)}
     * on a "complex" feature, involving inheritance and property overriding.
     */
    @Test
    @DependsOnMethod({"testSimpleValues", "testSimpleProperties"})
    public void testComplexFeature() {
        feature = createFeature(DefaultFeatureTypeTest.worldMetropolis());
        setAttributeValue("city", "Utopia", "New York");
        setAttributeValue("population", null, 8405837); // Estimation for 2013.
        /*
         * Switch to 'getProperty' mode only after we have set at least one value,
         * in order to test the conversion of existing values to property instances.
         */
        getValuesFromProperty = true;
        setAttributeValue("isGlobal", null, Boolean.TRUE);
        final SimpleInternationalString region = new SimpleInternationalString("State of New York");
        setAttributeValue("region", null, region);
        /*
         * In our 'metropolis' feature type, the region can be any CharSequence. But 'worldMetropolis'
         * feature type overrides the region property with a restriction to InternationalString.
         * Verifiy that this restriction is checked.
         */
        try {
            feature.setPropertyValue("region", "State of New York");
        } catch (ClassCastException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("region"));
            assertTrue(message, message.contains("String"));
        }
        assertSame("region", region, getAttributeValue("region"));
        assertValid();
        testSerialization();
        try {
            testClone("population", 8405837, 8405838); // A birth...
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Asserts that {@link AbstractFeature#quality()} reports no anomaly.
     */
    private void assertValid() {
        for (final Element report : feature.quality().getReports()) {
            for (final Result result : report.getResults()) {
                assertInstanceOf("result", ConformanceResult.class, result);
                assertTrue("result.pass", ((ConformanceResult) result).pass());
            }
        }
    }

    /**
     * Tests the {@link AbstractFeature#clone()} method on the current {@link #feature} instance.
     * This method is invoked from other test methods using the existing feature instance in an opportunist way.
     *
     * @param  property The name of a property to change.
     * @param  oldValue The old value of the given property.
     * @param  newValue The new value of the given property.
     * @throws CloneNotSupportedException Should never happen.
     */
    private void testClone(final String property, final Object oldValue, final Object newValue)
            throws CloneNotSupportedException
    {
        final AbstractFeature clone = feature.clone();
        assertNotSame("clone",      clone, feature);
        assertTrue   ("equals",     clone.equals(feature));
        assertTrue   ("hashCode",   clone.hashCode() == feature.hashCode());
        setAttributeValue(property, oldValue, newValue);
        assertEquals (property,     oldValue, clone  .getPropertyValue(property));
        assertEquals (property,     newValue, feature.getPropertyValue(property));
        assertFalse  ("equals",     clone.equals(feature));
        assertFalse  ("hashCode",   clone.hashCode() == feature.hashCode());
    }

    /**
     * Tests serialization of current {@link #feature} instance.
     * This method is invoked from other test methods using the existing feature instance in an opportunist way.
     */
    private void testSerialization() {
        assertNotSame(feature, assertSerializedEquals(feature));
    }
}
