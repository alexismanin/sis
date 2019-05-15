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
package org.apache.sis.internal.referencing.j2d;

import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.PathIterator;
import org.apache.sis.util.Static;

import static java.lang.Math.abs;
import static java.lang.Math.sqrt;
import static java.lang.Math.hypot;
import static java.lang.Double.isInfinite;


/**
 * Static methods operating on shapes from the {@link java.awt.geom} package.
 *
 * @author  Martin Desruisseaux (MPO, IRD, Geomatys)
 * @version 1.0
 * @since   0.5
 * @module
 */
public final class ShapeUtilities extends Static {
    /**
     * Threshold value for determining whether two points are the same, or whether two lines are colinear.
     */
    private static final double EPS = 1E-6;

    /**
     * Do not allow instantiation of this class.
     */
    private ShapeUtilities() {
    }

    /**
     * Returns the intersection point between two line segments. The lines do not continue to infinity;
     * if the intersection does not occur between the ending points {@linkplain Line2D#getP1() P1} and
     * {@linkplain Line2D#getP2() P2} of the two line segments, then this method returns {@code null}.
     *
     * @param  ax1  <var>x</var> value of the first point on the first  line.
     * @param  ay1  <var>y</var> value of the first point on the first  line.
     * @param  ax2  <var>x</var> value of the last  point on the first  line.
     * @param  ay2  <var>y</var> value of the last  point on the first  line.
     * @param  bx1  <var>x</var> value of the first point on the second line.
     * @param  by1  <var>y</var> value of the first point on the second line.
     * @param  bx2  <var>x</var> value of the last  point on the second line.
     * @param  by2  <var>y</var> value of the last  point on the second line.
     * @return the intersection point, or {@code null} if none.
     *
     * @see org.apache.sis.geometry.Shapes2D#intersectionPoint(Line2D, Line2D)
     */
    public static Point2D.Double intersectionPoint(final double ax1, final double ay1, double ax2, double ay2,
                                                   final double bx1, final double by1, double bx2, double by2)
    {
        ax2 -= ax1;
        ay2 -= ay1;
        bx2 -= bx1;
        by2 -= by1;
        double x = ay2 * bx2;
        double y = ax2 * by2;
        /*
         * The above (x,y) coordinate is temporary. If and only if the two line are parallel, then x == y.
         * Following code computes the real (x,y) coordinates of the intersection point.
         */
        x = ((by1-ay1) * (ax2*bx2) + x*ax1 - y*bx1) / (x-y);
        y = abs(bx2) > abs(ax2) ?
                (by2 / bx2) * (x - bx1) + by1 :
                (ay2 / ax2) * (x - ax1) + ay1;
        /*
         * The '!=0' expressions below are important for avoiding rounding errors with
         * horizontal or vertical lines. The '!' are important for handling NaN values.
         */
        if (ax2 != 0 && !(ax2 < 0 ? (x <= ax1 && x >= ax1 + ax2) : (x >= ax1 && x <= ax1 + ax2))) return null;
        if (bx2 != 0 && !(bx2 < 0 ? (x <= bx1 && x >= bx1 + bx2) : (x >= bx1 && x <= bx1 + bx2))) return null;
        if (ay2 != 0 && !(ay2 < 0 ? (y <= ay1 && y >= ay1 + ay2) : (y >= ay1 && y <= ay1 + ay2))) return null;
        if (by2 != 0 && !(by2 < 0 ? (y <= by1 && y >= by1 + by2) : (y >= by1 && y <= by1 + by2))) return null;
        return new Point2D.Double(x,y);
    }

    /**
     * Returns the point on the given {@code line} segment which is closest to the given {@code point}.
     * Let {@code result} be the returned point. This method guarantees (except for rounding errors) that:
     *
     * <ul>
     *   <li>{@code result} is a point on the {@code line} segment. It is located between the
     *       {@linkplain Line2D#getP1() P1} and {@linkplain Line2D#getP2() P2} ending points
     *       of that line segment.</li>
     *   <li>The distance between the {@code result} point and the given {@code point} is
     *       the shortest distance among the set of points meeting the previous condition.
     *       This distance can be obtained with {@code point.distance(result)}.</li>
     * </ul>
     *
     * @param  x1  <var>x</var> value of the first point on the line.
     * @param  y1  <var>y</var> value of the first point on the line.
     * @param  x2  <var>x</var> value of the last  point on the line.
     * @param  y2  <var>y</var> value of the last  point on the line.
     * @param  x   <var>x</var> value of a point close to the given line.
     * @param  y   <var>y</var> value of a point close to the given line.
     * @return the nearest point on the given line.
     *
     * @see #colinearPoint(double,double , double,double , double,double , double)
     *
     * @see org.apache.sis.geometry.Shapes2D#nearestColinearPoint(Line2D, Point2D)
     */
    public static Point2D.Double nearestColinearPoint(final double x1, final double y1,
                                                      final double x2, final double y2,
                                                            double x,        double y)
    {
        final double slope = (y2-y1) / (x2-x1);
        if (!isInfinite(slope)) {
            final double yx0 = (y2 - slope*x2);                     // Value of y at x=0.
            x = ((y - yx0) * slope + x) / (slope*slope + 1);
            y = yx0 + x*slope;
        } else {
            x = x2;
        }
        if (x1 <= x2) {
            if (x < x1) x = x1;
            if (x > x2) x = x2;
        } else {
            if (x > x1) x = x1;
            if (x < x2) x = x2;
        }
        if (y1 <= y2) {
            if (y < y1) y = y1;
            if (y > y2) y = y2;
        } else {
            if (y > y1) y = y1;
            if (y < y2) y = y2;
        }
        return new Point2D.Double(x,y);
    }

    /**
     * Returns a point on the given {@code line} segment located at the given {@code distance}
     * from that line. Let {@code result} be the returned point. If {@code result} is not null,
     * then this method guarantees (except for rounding error) that:
     *
     * <ul>
     *   <li>{@code result} is a point on the {@code line} segment. It is located between
     *       the {@linkplain Line2D#getP1() P1} and {@linkplain Line2D#getP2() P2} ending
     *       points of that line segment.</li>
     *   <li>The distance between the {@code result} and the given {@code point} is exactly
     *       equal to {@code distance}.</li>
     * </ul>
     *
     * If no result point meets those conditions, then this method returns {@code null}.
     * If two result points met those conditions, then this method returns the point
     * which is the closest to {@code line.getP1()}.
     *
     * @param  x1  <var>x</var> value of the first point on the line.
     * @param  y1  <var>y</var> value of the first point on the line.
     * @param  x2  <var>x</var> value of the last  point on the line.
     * @param  y2  <var>y</var> value of the last  point on the line.
     * @param  x   <var>x</var> value of a point close to the given line.
     * @param  y   <var>y</var> value of a point close to the given line.
     * @param  distance  the distance between the given point and the point to be returned.
     * @return a point on the given line located at the given distance from the given point.
     *
     * @see #nearestColinearPoint(double,double , double,double , double,double)
     *
     * @see org.apache.sis.geometry.Shapes2D#colinearPoint(Line2D, Point2D, double)
     */
    public static Point2D.Double colinearPoint(double x1, double y1, double x2, double y2,
                                               double x, double y, double distance)
    {
        final double ox1 = x1;
        final double oy1 = y1;
        final double ox2 = x2;
        final double oy2 = y2;
        distance *= distance;
        if (x1 == x2) {
            double dy = x1 - x;
            dy = sqrt(distance - dy*dy);
            y1 = y - dy;
            y2 = y + dy;
        } else if (y1 == y2) {
            double dx = y1 - y;
            dx = sqrt(distance - dx*dx);
            x1 = x - dx;
            x2 = x + dx;
        } else {
            final double m  = (y1-y2) / (x2-x1);
            final double y0 = (y2-y) + m*(x2-x);
            final double B  = m * y0;
            final double A  = m*m + 1;
            final double C  = sqrt(B*B + A*(distance - y0*y0));
            x1 = (B+C) / A;
            x2 = (B-C) / A;
            y1 = y + y0 - m*x1;
            y2 = y + y0 - m*x2;
            x1 += x;
            x2 += x;
        }
        boolean in1, in2;
        if (oy1 > oy2) {
            in1 = (y1 <= oy1 && y1 >= oy2);
            in2 = (y2 <= oy1 && y2 >= oy2);
        } else {
            in1 = (y1 >= oy1 && y1 <= oy2);
            in2 = (y2 >= oy1 && y2 <= oy2);
        }
        if (ox1 > ox2) {
            in1 &= (x1 <= ox1 && x1 >= ox2);
            in2 &= (x2 <= ox1 && x2 >= ox2);
        } else {
            in1 &= (x1 >= ox1 && x1 <= ox2);
            in2 &= (x2 >= ox1 && x2 <= ox2);
        }
        if (!in1 && !in2) return null;
        if (!in1) return new Point2D.Double(x2,y2);
        if (!in2) return new Point2D.Double(x1,y1);
        x = x1 - ox1;
        y = y1 - oy1;
        final double d1 = x*x + y*y;
        x = x2 - ox1;
        y = y2 - oy1;
        final double d2 = x*x + y*y;
        if (d1 > d2) return new Point2D.Double(x2,y2);
        else         return new Point2D.Double(x1,y1);
    }

    /**
     * Returns a quadratic curve passing by the 3 given points. There is an infinity of quadratic curves passing by
     * 3 points. We can express the curve we are looking for as a parabolic equation of the form {@code y=ax²+bx+c}
     * but where the <var>x</var> axis is not necessarily horizontal. The orientation of the <var>x</var> axis in
     * the above equation is determined by the {@code horizontal} parameter:
     *
     * <ul>
     *   <li>A value of {@code true} means that the <var>x</var> axis must be horizontal. The quadratic curve
     *       will then looks like an ordinary parabolic curve as we see in mathematic school book.</li>
     *   <li>A value of {@code false} means that the <var>x</var> axis must be parallel to the
     *       line segment joining the {@code P0} and {@code P2} ending points.</li>
     * </ul>
     *
     * Note that if {@code P0.y == P2.y}, then both {@code horizontal} values produce the same result.
     *
     * @param  x1  <var>x</var> value of the starting point.
     * @param  y1  <var>y</var> value of the starting point.
     * @param  px  <var>x</var> value of a passing point.
     * @param  py  <var>y</var> value of a passing point.
     * @param  x2  <var>x</var> value of the ending point.
     * @param  y2  <var>y</var> value of the ending point.
     * @param  horizontal  if {@code true}, the <var>x</var> axis is considered horizontal while computing the
     *         {@code y=ax²+bx+c} equation terms. If {@code false}, it is considered parallel to the line
     *         joining the {@code P0} and {@code P2} points.
     * @return a quadratic curve passing by the given points. The curve starts at {@code P0} and ends at {@code P2}.
     *         If two points are too close or if the three points are colinear, then this method returns {@code null}.
     *
     * @todo This method is used by Geotk (a sandbox for code that may migrate to SIS), but not yet by SIS.
     *       We temporarily keep this code here, but may delete or move it elsewhere in a future SIS version
     *       depending whether we port to SIS the sandbox code.
     */
    public static QuadCurve2D.Double fitParabol(final double x1, final double y1,
                                                final double px, final double py,
                                                final double x2, final double y2,
                                                final boolean horizontal)
    {
        final Point2D.Double p = parabolicControlPoint(x1, y1, px, py, x2, y2, horizontal);
        return (p != null) ? new QuadCurve2D.Double(x1, y1, p.x, p.y, x2, y2) : null;
    }

    /**
     * Returns the control point of a quadratic curve passing by the 3 given points. There is an infinity of quadratic
     * curves passing by 3 points. We can express the curve we are looking for as a parabolic equation of the form
     * {@code y = ax²+bx+c}, but the <var>x</var> axis is not necessarily horizontal. The <var>x</var> axis orientation
     * in the above equation is determined by the {@code horizontal} parameter:
     *
     * <ul>
     *   <li>A value of {@code true} means that the <var>x</var> axis must be horizontal.
     *       The quadratic curve will then look like an ordinary parabolic curve as we see
     *       in mathematic school book.</li>
     *   <li>A value of {@code false} means that the <var>x</var> axis must be parallel to the
     *       line segment joining the {@code P0} and {@code P2} ending points.</li>
     * </ul>
     *
     * Note that if {@code P0.y == P2.y}, then both {@code horizontal} values produce the same result.
     *
     * @param  x1  <var>x</var> value of the starting point.
     * @param  y1  <var>y</var> value of the starting point.
     * @param  px  <var>x</var> value of a passing point.
     * @param  py  <var>y</var> value of a passing point.
     * @param  x2  <var>x</var> value of the ending point.
     * @param  y2  <var>y</var> value of the ending point.
     * @param  horizontal  if {@code true}, the <var>x</var> axis is considered horizontal while computing the
     *         {@code y = ax²+bx+c} equation terms. If {@code false}, it is considered parallel to the line
     *         joining the {@code P0} and {@code P2} points.
     * @return the control point of a quadratic curve passing by the given points. The curve starts at {@code (x0,y0)}
     *         and ends at {@code (x2,y2)}. If two points are too close or if the three points are colinear, then this
     *         method returns {@code null}.
     */
    public static Point2D.Double parabolicControlPoint(final double x1, final double y1,
            double px, double py, double x2, double y2, final boolean horizontal)
    {
        /*
         * Apply a translation in such a way that (x0,y0) become the coordinate system origin.
         * After this translation, we shall not use (x0,y0) until we are done.
         */
        px -= x1;
        py -= y1;
        x2 -= x1;
        y2 -= y1;
        if (horizontal) {
            final double a = (y2 - py*x2/px) / (x2-px);     // Actually "a*x2"
            final double check = abs(a);
            if (!(check <= 1/EPS)) return null;             // Two points have the same coordinates.
            if (!(check >=   EPS)) return null;             // The three points are co-linear.
            final double b = y2/x2 - a;
            px = (1 + b/(2*a))*x2 - y2/(2*a);
            py = y1 + b*px;
            px += x1;
        } else {
            /*
             * Apply a rotation in such a way that (x2,y2)
             * lies on the x axis, i.e. y2 = 0.
             */
            final double rx2 = x2;
            final double ry2 = y2;
            x2 = hypot(x2,y2);
            y2 = (px*rx2 + py*ry2) / x2;                    // use 'y2' as a temporary variable for 'x1'
            py = (py*rx2 - px*ry2) / x2;
            px = y2;
//          y2 = 0;                                         // Could be set to that value, but not used.
            /*
             * Now compute the control point coordinates in our new coordinate system axis.
             */
            final double x = 0.5;                           // Actually "x/x2"
            final double y = (py*x*x2) / (px*(x2-px));      // Actually "y/y2"
            final double check = abs(y);
            if (!(check <= 1/EPS)) return null;             // Two points have the same coordinates.
            if (!(check >=   EPS)) return null;             // The three points are co-linear.
            /*
             * Applies the inverse rotation then a translation to bring
             * us back to the original coordinate system.
             */
            px = (x*rx2 - y*ry2) + x1;
            py = (y*rx2 + x*ry2) + y1;
        }
        return new Point2D.Double(px,py);
    }

    /**
     * Returns a Bézier curve passing by the given points and with the given derivatives at end points.
     * The cubic curve equation is:
     *
     * <blockquote>B(t) = (1-t)³⋅P₁ + 3(1-t)²t⋅C₁ + 3(1-t)t²⋅C₂ + t³⋅P₂</blockquote>
     *
     * where t ∈ [0…1], P₁ and P₂ are end points of the curve and C₁ and C₂ are control points generally not on the curve.
     * If the full equation is required for representing the curve, then this method builds a {@link CubicCurve2D}.
     * If the same curve can be represented by a quadratic curve, then this method returns a {@link QuadCurve2D}.
     * If the curve is actually a straight line, then this method returns a {@link Line2D}.
     *
     * <p>The (x₁,y₁) arguments give the coordinates of point P₁ at <var>t</var>=0.
     * The (x<sub>m</sub>,y<sub>m</sub>) arguments give the coordinates of the point at <var>t</var>=½.
     * The (x₂,y₂) arguments give the coordinates of point P₂ at <var>t</var>=1.</p>
     *
     * @param  x1  <var>x</var> value of the starting point.
     * @param  y1  <var>y</var> value of the starting point.
     * @param  xm  <var>x</var> value of the mid-point.
     * @param  ym  <var>y</var> value of the mid-point.
     * @param  x2  <var>x</var> value of the ending point.
     * @param  y2  <var>y</var> value of the ending point.
     * @param  α1  the derivative (∂y/∂x) at starting point.
     * @param  α2  the derivative (∂y/∂x) at ending point.
     * @param  εx  maximal distance on <var>x</var> axis between the cubic Bézier curve and quadratic or linear simplifications.
     * @param  εy  maximal distance on <var>y</var> axis between the cubic Bézier curve and quadratic or linear simplifications.
     * @return the Bézier curve passing by the 3 given points and having the given derivatives at end points.
     *
     * @see <a href="https://pomax.github.io/bezierinfo/">A Primer on Bézier Curves</a>
     *
     * @since 1.0
     */
    public static Shape bezier(final double x1, final double y1,
                                     double xm,       double ym,
                               final double x2, final double y2,
                               final double α1, final double α2,
                               final double εx, final double εy)
    {
        /*
         * Equations in this method are simplified as if (x1,y1) coordinates are (0,0).
         * Adjust (xm,ym) and (x2,y2) consequently. If derivatives are equal, equation
         * for cubic curve will not work (division by zero). But we can return a line
         * instead if derivatives are equal to Δy/Δx slope and mid-point is colinear.
         */
        xm -= x1;
        ym -= y1;
        final double Δx = x2 - x1;
        final double Δy = y2 - y1;
        final boolean isVertical = abs(Δx) <= εx;
        if (isVertical || ((isInfinite(α1) || abs(Δx*α1 - Δy) <= εy)
                       &&  (isInfinite(α2) || abs(Δx*α2 - Δy) <= εy)))
        {
            /*
             * Following tests are partially redundant with above tests, but may detect a larger
             * error than above tests did. They are also necessary if a derivative was infinite,
             * or if `isVertical` was true.
             */
            final boolean isHorizontal = abs(Δy) <= εy;
            if (isHorizontal || ((α1 == 0 || abs(Δy/α1 - Δx) <= εx)
                             &&  (α2 == 0 || abs(Δy/α2 - Δx) <= εx)))
            {
                final double slope = Δy / Δx;
                if ((isVertical   || abs(xm*slope - ym) <= εy) &&
                    (isHorizontal || abs(ym/slope - xm) <= εx))
                {
                    return new Line2D.Double(x1, y1, x2, y2);
                }
            }
        }
        /*
         * Bezier curve equation for starting point P₀, ending point P₃ and control points P₁ and P₂
         * (note: not the same numbers than the ones in arguments and variables used in this method):
         *
         * t ∈ [0…1]:  B(t)  =  (1-t)³⋅P₀ + 3(1-t)²t⋅P₁ + 3(1-t)t²⋅P₂ + t³⋅P₃
         * Midpoint:   B(½)  =  ⅛⋅(P₀ + P₃) + ⅜⋅(P₁ + P₂)
         *
         * Notation:   (x₀, y₀)   are coordinates of P₀ (same rule for P₁, P₂, P₃).
         *             (xm, ym)   are coordinates of midpoint.
         *             α₀ and α₃  are derivative (∂y/∂x) at P₀ and P₃ respectively.
         *
         * Some relationships:
         *
         *     xm = ⅛⋅(x₀ + x₃) + ⅜⋅(x₁ + x₂)
         *     (y₁ - y₀) / (x₁ - x₀) = α₀
         *     (y₃ - y₂) / (x₃ - x₂) = α₃
         *
         * Setting (x₀,y₀) = (0,0) for simplicity and rearranging above equations:
         *
         *     x₁ = (8⋅xm - x₃)/3 - x₂              where    x₂ = x₃ - (y₃ - y₂)/α₃
         *     x₁ = (8⋅xm - 4⋅x₃)/3 + (y₃ - y₂)/α₃
         *
         * Doing similar rearrangement for y:
         *
         *     y₂ = (8⋅ym - y₃)/3 - y₁    where    y₁ = x₁⋅α₀
         *     y₂ = (8⋅ym - y₃)/3 - x₁⋅α₀
         *
         * Putting together and isolating x₁:
         *
         *      x₁ = (8⋅xm - 4⋅x₃)/3 + (x₁⋅α₀ - (8⋅ym - 4⋅y₃)/3)/α₃
         *      x₁ = (8⋅xm - 4⋅x₃ - (8⋅ym - 4⋅y₃)/α₃) / 3(1 - α₀/α₃)
         *
         * x₁ and x₂ are named cx1 and cx2 in the code below ("c" for "control").
         * x₀ and x₃ are named x1 and x2 for consistency with Java2D usage.
         * Same changes apply to y.
         */
        final double cx1 = ((8*xm - 4*Δx)*α2 - (8*ym - 4*Δy)) / (3*(α2 - α1));
        final double cy1 = cx1 * α1;
        final double cx2 = (8*xm - Δx)/3 - cx1;
        final double cy2 = Δy - (Δx - cx2)*α2;
        /*
         * At this point we got the control points (cx1,cy1) and (cx2,cy2). Verify if we can simplify
         * cubic curbe to a quadratic curve. If we were elevating the degree from quadratic to cubic,
         * the control points C₁ and C₂ would be: (Q is the control point of the quadratic curve)
         *
         *     C₁  =  ⅓P₁ + ⅔Q
         *     C₂  =  ⅓P₂ + ⅔Q
         *
         * We want Q instead, which can be computed in two ways:
         *
         *     Q   =  (3C₁ - P₁)/2
         *     Q   =  (3C₂ - P₂)/2
         *
         * We compute Q both ways and check if they are close enough to each other:
         *
         *     ΔQ  =  (3⋅(C₂ - C₁) - (P₂ - P₁))/2
         */
        final double Δqx = (3*(cx2 - cx1) - Δx)/2;          // P₁ set to zero.
        if (abs(Δqx) <= εx) {
            final double Δqy = (3*(cy2 - cy1) - Δy)/2;
            if (abs(Δqy) <= εy) {
                final double qx = (3*cx1 + Δqx)/2;          // Take average of 2 control points.
                final double qy = (3*cy1 + Δqy)/2;
                return new QuadCurve2D.Double(x1, y1, qx+x1, qy+y1, x2, y2);
            }
        }
        return new CubicCurve2D.Double(x1, y1, cx1+x1, cy1+y1, cx2+x1, cy2+y1, x2, y2);
    }

    /**
     * Returns a point on the given linear, quadratic or cubic Bézier curve.
     *
     * @param  bezier  a {@link Line2D}, {@link QuadCurve2D} or {@link CubicCurve2D}.
     * @param  t       a parameter from 0 to 1 inclusive.
     * @return a point on the curve for the given <var>t</var> parameter.
     *
     * @see <a href="https://en.wikipedia.org/wiki/B%C3%A9zier_curve">Bézier curve on Wikipedia</a>
     */
    public static Point2D.Double pointOnBezier(final Shape bezier, final double t) {
        final double x, y;
        final double mt = 1 - t;
        if (bezier instanceof Line2D) {
            final Line2D z = (Line2D) bezier;
            x = mt * z.getX1()  +  t * z.getX2();
            y = mt * z.getY1()  +  t * z.getY2();
        } else if (bezier instanceof QuadCurve2D) {
            final QuadCurve2D z = (QuadCurve2D) bezier;
            final double a = mt * mt;
            final double b = mt * t * 2;
            final double c =  t * t;
            x = a * z.getX1()  +  b * z.getCtrlX()  +  c * z.getX2();
            y = a * z.getY1()  +  b * z.getCtrlY()  +  c * z.getY2();
        } else if (bezier instanceof CubicCurve2D) {
            final CubicCurve2D z = (CubicCurve2D) bezier;
            final double a = mt * mt * mt;
            final double b = mt * mt * t  * 3;
            final double c = mt * (t * t) * 3;
            final double d =  t *  t * t;
            x = a * z.getX1()  +  b * z.getCtrlX1()  +  c * z.getCtrlX2()  +  d * z.getX2();
            y = a * z.getY1()  +  b * z.getCtrlY1()  +  c * z.getCtrlY2()  +  d * z.getY2();
        } else {
            throw new IllegalArgumentException();
        }
        return new Point2D.Double(x, y);
    }

    /**
     * Returns the center of a circle passing by the 3 given points. The distance between the returned
     * point and any of the given points will be constant; it is the circle radius.
     *
     * @param  x1  <var>x</var> value of the first  point.
     * @param  y1  <var>y</var> value of the first  point.
     * @param  x2  <var>x</var> value of the second point.
     * @param  y2  <var>y</var> value of the second point.
     * @param  x3  <var>x</var> value of the third  point.
     * @param  y3  <var>y</var> value of the third  point.
     * @return the center of a circle passing by the given points.
     *
     * @see org.apache.sis.geometry.Shapes2D#circle(Point2D, Point2D, Point2D)
     */
    public static Point2D.Double circleCentre(double x1, double y1,
                                              double x2, double y2,
                                              double x3, double y3)
    {
        x2 -= x1;
        x3 -= x1;
        y2 -= y1;
        y3 -= y1;
        final double sq2 = (x2*x2 + y2*y2);
        final double sq3 = (x3*x3 + y3*y3);
        final double x   = (y2*sq3 - y3*sq2) / (y2*x3 - y3*x2);
        return new Point2D.Double(x1 + 0.5*x, y1 + 0.5*(sq2 - x*x2)/y2);
    }

    /**
     * Attempts to replace an arbitrary shape by one of the standard Java2D constructs.
     * For example if the given {@code path} is a {@link Path2D} containing only a single
     * line or a quadratic curve, then this method replaces it by a {@link Line2D} or
     * {@link QuadCurve2D} object respectively.
     *
     * @param  path  the shape to replace by a simpler Java2D construct.
     *         This is generally an instance of {@link Path2D}, but not necessarily.
     * @return a simpler Java construct, or {@code path} if no better construct is proposed.
     */
    public static Shape toPrimitive(final Shape path) {
        final PathIterator it = path.getPathIterator(null);
        if (!it.isDone()) {
            final double[] buffer = new double[6];
            if (it.currentSegment(buffer) == PathIterator.SEG_MOVETO) {
                it.next();
                if (!it.isDone()) {
                    final double x1 = buffer[0];
                    final double y1 = buffer[1];
                    final int code = it.currentSegment(buffer);
                    it.next();
                    if (it.isDone()) {
                        if (isFloat(path)) {
                            switch (code) {
                                case PathIterator.SEG_LINETO:  return new       Line2D.Float((float) x1, (float) y1, (float) buffer[0], (float) buffer[1]);
                                case PathIterator.SEG_QUADTO:  return new  QuadCurve2D.Float((float) x1, (float) y1, (float) buffer[0], (float) buffer[1], (float) buffer[2], (float) buffer[3]);
                                case PathIterator.SEG_CUBICTO: return new CubicCurve2D.Float((float) x1, (float) y1, (float) buffer[0], (float) buffer[1], (float) buffer[2], (float) buffer[3], (float) buffer[4], (float) buffer[5]);
                            }
                        } else {
                            switch (code) {
                                case PathIterator.SEG_LINETO:  return new       Line2D.Double(x1,y1, buffer[0], buffer[1]);
                                case PathIterator.SEG_QUADTO:  return new  QuadCurve2D.Double(x1,y1, buffer[0], buffer[1], buffer[2], buffer[3]);
                                case PathIterator.SEG_CUBICTO: return new CubicCurve2D.Double(x1,y1, buffer[0], buffer[1], buffer[2], buffer[3], buffer[4], buffer[5]);
                            }
                        }
                    }
                }
            }
        }
        return path;
    }

    /**
     * Returns {@code true} if the given shape is presumed backed by primitive {@code float} values.
     * The given object should be an instance of {@link Shape} or {@link Point2D}.
     * This method use heuristic rules based on class name used in Java2D library.
     *
     * @param  path  the shape for which to determine the backing primitive type.
     * @return {@code true} if the given shape is presumed backed by {@code float} type.
     */
    public static boolean isFloat(final Object path) {
        return path.getClass().getSimpleName().equals("Float");
    }
}
