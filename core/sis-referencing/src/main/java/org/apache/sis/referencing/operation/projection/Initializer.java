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
package org.apache.sis.referencing.operation.projection;

import java.util.Map;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.referencing.operation.OperationMethod;
import org.apache.sis.internal.referencing.provider.MapProjection;
import org.apache.sis.parameter.Parameters;
import org.apache.sis.internal.util.DoubleDouble;
import org.apache.sis.referencing.operation.matrix.MatrixSIS;
import org.apache.sis.referencing.operation.transform.ContextualParameters;
import org.apache.sis.referencing.operation.projection.NormalizedProjection.ParameterRole;

import static java.lang.Math.*;
import static org.apache.sis.util.ArgumentChecks.ensureNonNull;


/**
 * Helper class for map projection constructions, providing formulas normally needed only at construction time.
 * Since map projection constructions should not happen very often, we afford using double-double arithmetic here.
 * The main intend is not to provide more accurate coordinate conversions (while it may be a nice side-effect),
 * but rather to increase the chances that the concatenations of (de)normalization matrices with the matrices of
 * other transforms give back identity matrices when such result is expected.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.6
 * @version 0.6
 * @module
 */
final class Initializer {
    /**
     * The parameters used for creating the map projection.
     * This object will be stored in the map projection.
     *
     * @see NormalizedProjection#getContextualParameters()
     */
    final ContextualParameters context;

    /**
     * The user-supplied parameters, stored temporarily while we transfer the information to {@link #context}.
     */
    final Parameters parameters;

    /**
     * The square of excentricity: ℯ² = (a²-b²)/a² where
     * <var>ℯ</var> is the {@linkplain #excentricity excentricity},
     * <var>a</var> is the <cite>semi-major</cite> axis length and
     * <var>b</var> is the <cite>semi-minor</cite> axis length.
     */
    final DoubleDouble excentricitySquared;

    /**
     * Map projection variant. This is a convenience field left at
     * the discretion of {@link NormalizedProjection} subclasses.
     */
    final byte variant;

    /**
     * Creates a new initializer.
     */
    Initializer(final OperationMethod method, final Parameters parameters,
            final Map<ParameterRole, ? extends ParameterDescriptor<Double>> roles,
            final byte variant)
    {
        ensureNonNull("method",     method);
        ensureNonNull("parameters", parameters);
        ensureNonNull("roles",      roles);
        this.context    = new ContextualParameters(method);
        this.parameters = parameters;
        this.variant    = variant;
        /*
         * Note: we do not use Map.getOrDefault(K,V) below because the user could have explicitly associated
         * a null value to keys (we are paranoiac...) and because it conflicts with the "? extends" part of
         * in this constructor signature.
         */
        ParameterDescriptor<Double> semiMajor = roles.get(ParameterRole.SEMI_MAJOR);
        ParameterDescriptor<Double> semiMinor = roles.get(ParameterRole.SEMI_MINOR);
        if (semiMajor == null) semiMajor = MapProjection.SEMI_MAJOR;
        if (semiMinor == null) semiMinor = MapProjection.SEMI_MINOR;

        final double a  = getAndStore(semiMajor);
        final double b  = getAndStore(semiMinor);
        final double λ0 = getAndStore(roles.get(ParameterRole.CENTRAL_MERIDIAN));
        final double fe = getAndStore(roles.get(ParameterRole.FALSE_EASTING))
                        - getAndStore(roles.get(ParameterRole.FALSE_WESTING));
        final double fn = getAndStore(roles.get(ParameterRole.FALSE_NORTHING))
                        - getAndStore(roles.get(ParameterRole.FALSE_SOUTHING));

        excentricitySquared = new DoubleDouble();
        final DoubleDouble k = new DoubleDouble(a);  // The value by which to multiply all results of normalized projection.
        if (a != b) {
            /*
             * Equivalent Java code for the following lines:
             *
             *     final double rs = b / a;
             *     excentricitySquared = 1 - (rs * rs);
             *
             * Test show that double-double arithmetic here makes a difference in the 3 last digits for WGS84 ellipsoid.
             * Those 3 digits are not significant since the parameter are not so accurate (furthermore the 'b' parameter
             * used below may have been computed from the inverse flattening factor).
             */
            final DoubleDouble rs = new DoubleDouble(b);
            final double eb = rs.error;
            rs.divide(k);    // rs = b/a
            rs.multiply(rs);
            excentricitySquared.value = 1;
            excentricitySquared.subtract(rs);

            final ParameterDescriptor<Double> radius = roles.get(ParameterRole.LATITUDE_OF_CONFORMAL_SPHERE_RADIUS);
            if (radius != null) {
                /*
                 * EPSG said: R is the radius of the sphere and will normally be one of the CRS parameters.
                 * If the figure of the earth used is an ellipsoid rather than a sphere then R should be calculated
                 * as the radius of the conformal sphere at the projection origin at latitude φ₀ using the formula
                 * for Rc given in section 1.2, table 3.
                 *
                 * Table 3 gives:
                 * Radius of conformal sphere Rc = a √(1 – ℯ²) / (1 – ℯ²⋅sin²φ)
                 *
                 * Using √(1 – ℯ²) = b/a we rewrite as: Rc = b / (1 – ℯ²⋅sin²φ)
                 *
                 * Equivalent Java code:
                 *
                 *     final double sinφ = sin(toRadians(parameters.doubleValue(radius)));
                 *     k = b / (1 - excentricitySquared * (sinφ*sinφ));
                 */
                final DoubleDouble t = new DoubleDouble(sin(toRadians(parameters.doubleValue(radius))), 0);
                t.multiply(t);
                t.multiply(excentricitySquared);
                k.clear();
                k.value = 1;
                k.subtract(t);
                k.inverseDivide(b, eb);
            }
        }
        context.normalizeGeographicInputs(λ0);
        final ParameterDescriptor<Double> scaleFactor = roles.get(ParameterRole.SCALE_FACTOR);
        if (scaleFactor != null) {
            k.multiply(getAndStore(scaleFactor));
        }
        final MatrixSIS denormalize = context.getMatrix(false);
        denormalize.convertAfter(0, k, new DoubleDouble(fe));
        denormalize.convertAfter(1, k, new DoubleDouble(fn));
    }

    /**
     * Gets a parameter value identified by the given descriptor and stores it in the {@link #context}.
     * A "contextual parameter" is a parameter that apply to the normalize → {@code this} → denormalize
     * chain as a whole. It does not really apply to this {@code NormalizedProjection} instance when taken alone.
     *
     * <p>This method performs the following actions:</p>
     * <ul>
     *   <li>Convert the value to the units specified by the descriptor.</li>
     *   <li>Ensure that the value is contained in the range specified by the descriptor.</li>
     *   <li>Store the value only if different than the default value.</li>
     * </ul>
     */
    final double getAndStore(final ParameterDescriptor<Double> descriptor) {
        if (descriptor == null) {
            return 0;   // Default value for all parameters except scale factor.
        }
        final double value = parameters.doubleValue(descriptor);    // Apply a unit conversion if needed.
        final Double defaultValue = descriptor.getDefaultValue();
        if (defaultValue == null || !defaultValue.equals(value)) {
            MapProjection.validate(descriptor, value);
            context.getOrCreate(descriptor).setValue(value);
        }
        return value;
    }

    /**
     * Same as {@link #getAndStore(Parameters, ParameterDescriptor)}, but returns the given default value
     * if the parameter is not specified.  This method shall be used only for parameters having a default
     * value more complex than what we can represent in {@link ParameterDescriptor#getDefaultValue()}.
     */
    final double getAndStore(final ParameterDescriptor<Double> descriptor, final double defaultValue) {
        final Double value = parameters.getValue(descriptor);   // Apply a unit conversion if needed.
        if (value == null) {
            return defaultValue;
        }
        MapProjection.validate(descriptor, value);
        context.parameter(descriptor.getName().getCode()).setValue(value);
        return value;
    }




    //////////////////////////////////////////////////////////////////////////////////////////
    ////////                                                                          ////////
    ////////                       FORMULAS FROM EPSG or SNYDER                       ////////
    ////////                                                                          ////////
    //////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Computes the reciprocal of the radius of curvature of the ellipsoid perpendicular to the meridian at latitude φ.
     * That radius of curvature is:
     *
     * <blockquote>ν = 1 / √(1 - ℯ²⋅sin²φ)</blockquote>
     *
     * This method returns 1/ν.
     *
     * <div class="section">Relationship with Snyder</div>
     * This is related to functions (14-15) from Snyder (used for computation of scale factors
     * at the true scale latitude) as below:
     *
     * <blockquote>m = cosφ / rν</blockquote>
     *
     * Special cases:
     * <ul>
     *   <li>If φ is 0°, then <var>m</var> is 1.</li>
     *   <li>If φ is ±90°, then <var>m</var> is 0 provided that we are not in the spherical case
     *       (otherwise we get {@link Double#NaN}).</li>
     * </ul>
     *
     * @param  sinφ The sine of the φ latitude in radians.
     * @return Reciprocal of the radius of curvature of the ellipsoid perpendicular to the meridian at latitude φ.
     */
    final DoubleDouble rν(final double sinφ) {
        /*
         * Equivalent Java code:
         *
         *     return sqrt(1 - excentricitySquared * (sinφ*sinφ));
         */
        final DoubleDouble t = new DoubleDouble(sinφ, 0);
        t.multiply(t);
        t.multiply(excentricitySquared);
        final double value = t.value;
        final double error = t.error;
        t.clear();
        t.value = 1;
        t.subtract(value, error);
        t.sqrt();
        return t;
    }
}