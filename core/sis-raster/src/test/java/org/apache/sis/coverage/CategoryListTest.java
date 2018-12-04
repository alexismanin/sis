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
package org.apache.sis.coverage;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Random;
import org.opengis.referencing.operation.MathTransform1D;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.referencing.operation.transform.MathTransforms;
import org.apache.sis.math.MathFunctions;
import org.apache.sis.measure.NumberRange;
import org.apache.sis.test.TestUtilities;
import org.apache.sis.test.TestCase;
import org.apache.sis.test.DependsOn;
import org.apache.sis.test.DependsOnMethod;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Tests {@link CategoryList}.
 *
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @version 1.0
 * @since   1.0
 * @module
 */
@DependsOn(CategoryTest.class)
public final strictfp class CategoryListTest extends TestCase {
    /**
     * Asserts that the specified categories are sorted.
     * This method ignores {@code NaN} values.
     */
    private static void assertSorted(final Category[] categories) {
        for (int i=1; i<categories.length; i++) {
            final Category current  = categories[i  ];
            final Category previous = categories[i-1];
            assertFalse( current.minimum >  current.maximum);
            assertFalse(previous.minimum > previous.maximum);
            assertFalse(Category.compare(previous.maximum, current.minimum) > 0);
        }
    }

    /**
     * Tests the checks performed by {@link CategoryList} constructor.
     */
    @Test
    public void testArgumentChecks() {
        final Set<Integer> padValues = new HashSet<>();
        Category[] categories = {
            new Category("No data", NumberRange.create( 0, true,  0, true), null, null, padValues),
            new Category("Land",    NumberRange.create(10, true, 10, true), null, null, padValues),
            new Category("Clouds",  NumberRange.create( 2, true,  2, true), null, null, padValues),
            new Category("Again",   NumberRange.create(10, true, 10, true), null, null, padValues)       // Range overlaps.
        };
        try {
            assertTrue(new CategoryList(categories.clone(), null).isPublic());
            fail("Should not have accepted range overlap.");
        } catch (IllegalArgumentException exception) {
            // This is the expected exception.
            final String message = exception.getMessage();
            assertTrue(message, message.contains("Land"));
            assertTrue(message, message.contains("Again"));
        }
        // Removes the wrong category. Now, construction should succeed.
        categories = Arrays.copyOf(categories, categories.length - 1);
        assertTrue("isPublic", new CategoryList(categories, null).isPublic());
        assertSorted(categories);
    }

    /**
     * Tests the {@link CategoryList#binarySearch(double[], double)} method.
     */
    @Test
    public void testBinarySearch() {
        final Random random = TestUtilities.createRandomNumberGenerator();
        for (int pass=0; pass<50; pass++) {
            final double[] array = new double[random.nextInt(32) + 32];
            int realNumberLimit = 0;
            for (int i=0; i<array.length; i++) {
                realNumberLimit += random.nextInt(10) + 1;
                array[i] = realNumberLimit;
            }
            realNumberLimit += random.nextInt(10);
            for (int i=0; i<100; i++) {
                final double searchFor = random.nextInt(realNumberLimit);
                assertEquals("binarySearch", Arrays.binarySearch(array, searchFor),
                                       CategoryList.binarySearch(array, searchFor));
            }
            /*
             * Previous test didn't tested NaN values (which is the main difference
             * between binarySearch method in Arrays and CategoryList). Now test it.
             */
            int nanOrdinalLimit = 0;
            realNumberLimit /= 2;
            for (int i = array.length / 2; i < array.length; i++) {
                nanOrdinalLimit += random.nextInt(10) + 1;
                array[i] = MathFunctions.toNanFloat(nanOrdinalLimit);
            }
            nanOrdinalLimit += random.nextInt(10);
            for (int i=0; i<100; i++) {
                final double search;
                if (random.nextBoolean()) {
                    search = random.nextInt(realNumberLimit);
                } else {
                    search = MathFunctions.toNanFloat(random.nextInt(nanOrdinalLimit));
                }
                int foundAt = CategoryList.binarySearch(array, search);
                if (foundAt >= 0) {
                    assertEquals(Double.doubleToRawLongBits(search),
                                 Double.doubleToRawLongBits(array[foundAt]), STRICT);
                } else {
                    foundAt = ~foundAt;
                    if (foundAt < array.length) {
                        final double after = array[foundAt];
                        assertFalse(search >= after);
                        if (Double.isNaN(search)) {
                            assertTrue("isNaN", Double.isNaN(after));
                        }
                    }
                    if (foundAt > 0) {
                        final double before = array[foundAt - 1];
                        assertFalse(search <= before);
                        if (!Double.isNaN(search)) {
                            assertFalse("isNaN", Double.isNaN(before));
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates an array of category for {@link #testSearch()} and {@link #testTransform()}.
     */
    private static Category[] categories() {
        final Set<Integer> padValues = new HashSet<>();
        return new Category[] {
            /*[0]*/ new Category("No data",     NumberRange.create(  0, true,   0, true), null, null, padValues),
            /*[1]*/ new Category("Land",        NumberRange.create(  7, true,   7, true), null, null, padValues),
            /*[2]*/ new Category("Clouds",      NumberRange.create(  3, true,   3, true), null, null, padValues),
            /*[3]*/ new Category("Temperature", NumberRange.create( 10, true, 100, false), (MathTransform1D) MathTransforms.linear(0.1, 5), null, padValues),
            /*[4]*/ new Category("Foo",         NumberRange.create(100, true, 120, false), (MathTransform1D) MathTransforms.linear( -1, 3), null, padValues)
        };
    }

    /**
     * Tests the sample values range and converged values range after construction of a list of categories.
     */
    @Test
    public void testRanges() {
        final CategoryList list = new CategoryList(categories(), null);
        assertTrue  ("isMinIncluded",            list.range.isMinIncluded());
        assertFalse ("isMaxIncluded",            list.range.isMaxIncluded());
        assertFalse ("converted.isMinIncluded",  list.converted.range.isMinIncluded());     // Because computed from maxValue before conversion.
        assertFalse ("converted.isMaxIncluded",  list.converted.range.isMaxIncluded());
        assertEquals("minValue",              0, ((Number) list.range          .getMinValue()).doubleValue(), STRICT);
        assertEquals("maxValue",            120, ((Number) list.range          .getMaxValue()).doubleValue(), STRICT);
        assertEquals("converted.minValue", -117, ((Number) list.converted.range.getMinValue()).doubleValue(), STRICT);
        assertEquals("converted.maxValue",   15, ((Number) list.converted.range.getMaxValue()).doubleValue(), STRICT);
        assertEquals("converted.minValue", -117, list.converted.range.getMinDouble(false), STRICT);
        assertEquals("converted.maxValue",   15, list.converted.range.getMaxDouble(false), STRICT);
        assertEquals("converted.minValue", -116, list.converted.range.getMinDouble(true),  CategoryTest.EPS);
        assertEquals("converted.maxValue", 14.9, list.converted.range.getMaxDouble(true),  CategoryTest.EPS);
    }

    /**
     * Tests the {@link CategoryList#search(double)} method.
     */
    @Test
    @DependsOnMethod("testBinarySearch")
    public void testSearch() {
        final Category[] categories = categories();
        final CategoryList list = new CategoryList(categories.clone(), null);
        assertTrue("containsAll", list.containsAll(Arrays.asList(categories)));
        /*
         * Checks category searches for values that are insides the range of a category.
         */
        assertSame(  "0", categories[0],           list.search(  0));
        assertSame(  "7", categories[1],           list.search(  7));
        assertSame(  "3", categories[2],           list.search(  3));
        assertSame(" 10", categories[3],           list.search( 10));
        assertSame(" 50", categories[3],           list.search( 50));
        assertSame("100", categories[4],           list.search(100));
        assertSame("110", categories[4],           list.search(110));
        assertSame(  "0", categories[0].converted, list.converted.search(MathFunctions.toNanFloat(  0)));
        assertSame(  "7", categories[1].converted, list.converted.search(MathFunctions.toNanFloat(  7)));
        assertSame(  "3", categories[2].converted, list.converted.search(MathFunctions.toNanFloat(  3)));
        assertSame(" 10", categories[3].converted, list.converted.search(  /* transform( 10) */     6 ));
        assertSame(" 50", categories[3].converted, list.converted.search(  /* transform( 50) */    10 ));
        assertSame("100", categories[4].converted, list.converted.search(  /* transform(100) */   -97 ));
        assertSame("110", categories[4].converted, list.converted.search(  /* transform(110) */  -107 ));
        /*
         * Checks values outside the range of any category. For direct conversion, no category shall be returned.
         * For inverse conversion, the nearest category shall be returned.
         */
        assertNull( "-1",                          list.search( -1));
        assertNull(  "2",                          list.search(  2));
        assertNull(  "4",                          list.search(  4));
        assertNull(  "9",                          list.search(  9));
        assertNull("120",                          list.search(120));
        assertNull("200",                          list.search(200));
        assertNull( "-1",                          list.converted.search(MathFunctions.toNanFloat(-1)));    // Nearest sample is 0
        assertNull(  "2",                          list.converted.search(MathFunctions.toNanFloat( 2)));    // Nearest sample is 3
        assertNull(  "4",                          list.converted.search(MathFunctions.toNanFloat( 4)));    // Nearest sample is 3
        assertNull(  "9",                          list.converted.search(MathFunctions.toNanFloat( 9)));    // Nearest sample is 10
        assertSame(  "9", categories[3].converted, list.converted.search( /* transform(  9) */   5.9 ));    // Nearest sample is 10
        assertSame("120", categories[4].converted, list.converted.search( /* transform(120) */  -117 ));    // Nearest sample is 119
        assertSame("200", categories[4].converted, list.converted.search( /* transform(200) */  -197 ));    // Nearest sample is 119
    }

    /**
     * Tests the {@link CategoryList#transform(double)} method.
     *
     * @throws TransformException if an error occurred while transforming a value.
     */
    @Test
    @DependsOnMethod("testSearch")
    public void testTransform() throws TransformException {
        final Random random = TestUtilities.createRandomNumberGenerator();
        final CategoryList list = new CategoryList(categories(), null);
        /*
         * Checks conversions. We verified in 'testSearch()' that correct categories are found for those values.
         */
        assertTrue  (  "0", Double.isNaN(list.transform(  0)));
        assertTrue  (  "7", Double.isNaN(list.transform(  7)));
        assertTrue  (  "3", Double.isNaN(list.transform(  3)));
        assertEquals( "10",           6, list.transform( 10), CategoryTest.EPS);
        assertEquals( "50",          10, list.transform( 50), CategoryTest.EPS);
        assertEquals("100",         -97, list.transform(100), CategoryTest.EPS);
        assertEquals("110",        -107, list.transform(110), CategoryTest.EPS);
        /*
         * Tests conversions using methods working on arrays.
         * We assume that the 'transform(double)' version can be used as a reference.
         */
        final double[] input   = new double[337];                   // A prime number, for more randomness.
        final double[] output0 = new double[input.length];
        final double[] output1 = new double[input.length];
        for (int i=0; i < input.length;) {
            final Category c = list.get(random.nextInt(list.size()));
            final int lower  =  (int) c.range.getMinDouble(true);
            final int span   = ((int) c.range.getMaxDouble(false)) - lower;
            int count = Math.min(random.nextInt(span + 5) + 1, input.length - i);
            while (--count >= 0) {
                input  [i] = random.nextInt(span) + lower;
                output0[i] = list.transform(input[i]);
                i++;
            }
        }
        list.transform(input, 0, output1, 0, input.length);
        compare(output0, output1);
        /*
         * Tests the transform using overlapping array.
         */
        System.arraycopy(input, 0, output1, 3, input.length-3);
        list.transform (output1, 3, output1, 0, input.length-3);
        System.arraycopy(output0, input.length-3, output1, input.length-3, 3);
        compare(output0, output1);
        /*
         * Implementation will do the following transform in reverse direction.
         */
        System.arraycopy(input, 3, output1, 0, input.length-3);
        list.transform (output1, 0, output1, 3, input.length-3);
        System.arraycopy(output0, 0, output1, 0, 3);
        compare(output0, output1);
        /*
         * Test inverse transfom.
         */
        list.inverse().transform(output0, 0, output0, 0, output0.length);
        for (int i=0; i<output0.length; i++) {
            final double expected = input[i];
            if (expected >= 10 && expected < 120) {
                // Values outside this range have been clamped.
                // They would usually not be equal.
                assertEquals("inverse", expected, output0[i], CategoryTest.EPS);
            }
        }
    }

    /**
     * Compares two arrays. Special comparison is performed for NaN values.
     */
    private static void compare(final double[] output0, final double[] output1) {
        assertEquals("length", output0.length, output1.length);
        for (int i=0; i<output0.length; i++) {
            final double expected = output0[i];
            final double actual   = output1[i];
            if (Double.isNaN(expected)) {
                final int bits1 = Float.floatToRawIntBits((float) expected);
                final int bits2 = Float.floatToRawIntBits((float)   actual);
                assertEquals(bits1, bits2);
            }
            assertEquals(expected, actual, CategoryTest.EPS);
        }
    }
}
