//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    W e d g e s B u i l d e r                                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2017. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.sheet.curve;

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.math.LineUtil;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sig.GradeImpacts;
import org.audiveris.omr.sig.inter.Inters;
import org.audiveris.omr.sig.inter.SegmentInter;
import org.audiveris.omr.sig.inter.WedgeInter;
import org.audiveris.omr.util.Dumping;
import org.audiveris.omr.util.Navigable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.List;

/**
 * Class {@code WedgesBuilder} retrieves the wedges (crescendo, diminuendo) out of
 * segments found in sheet skeleton.
 *
 * @author Hervé Bitteur
 */
public class WedgesBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(WedgesBuilder.class);

    //~ Instance fields ----------------------------------------------------------------------------
    /** The related sheet. */
    @Navigable(false)
    protected final Sheet sheet;

    /** Curves environment. */
    protected final Curves curves;

    /** Scale-dependent parameters. */
    private final Parameters params;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new WedgesBuilder object.
     *
     * @param curves curves environment
     */
    public WedgesBuilder (Curves curves)
    {
        this.curves = curves;
        sheet = curves.getSheet();
        params = new Parameters(sheet.getScale());
    }

    //~ Methods ------------------------------------------------------------------------------------
    //-------------//
    // buildWedges //
    //-------------//
    /**
     * Wedges look like "hair pins", composed of two converging lines.
     * <p>
     * The pair of lines of a wedge are of similar length (short or long) and rather horizontal.
     * By comparison, ending lines are isolated, long and strictly horizontal.
     */
    public void buildWedges ()
    {
        // Use an area on left end of a segment and look for compatible segments
        // Do the same on right end of segments
        List<SegmentInter> segments = curves.getSegments();

        for (final boolean rev : new boolean[]{true, false}) {
            Collections.sort(segments, rev ? Inters.byAbscissa : Inters.byRightAbscissa);

            for (int index = 0; index < segments.size(); index++) {
                SegmentInter s1 = segments.get(index);

                // Define the lookup area
                Rectangle area = getArea(s1.getInfo(), rev);
                double xMax = area.getMaxX();

                for (SegmentInter s2 : segments.subList(index + 1, segments.size())) {
                    Point sEnd = s2.getInfo().getEnd(rev);

                    if (area.contains(sEnd)) {
                        // Check compatibility
                        GradeImpacts impacts = computeImpacts(s1, s2, rev);

                        if ((impacts != null) && (impacts.getGrade() >= WedgeInter.getMinGrade())) {
                            createWedgeInter(s1, s2, rev, impacts);
                            segments.remove(s1);
                            segments.remove(s2);
                            index--;

                            break;
                        }
                    } else if (sEnd.getX() > xMax) {
                        break; // Since list is sorted by abscissa
                    }
                }
            }
        }
    }

    //----------------//
    // computeImpacts //
    //----------------//
    private GradeImpacts computeImpacts (SegmentInter s1,
                                         SegmentInter s2,
                                         boolean rev)
    {
        // Intrinsic segments impacts
        GradeImpacts imp1 = s1.getImpacts();
        double d1 = imp1.getGrade() / imp1.getIntrinsicRatio();
        GradeImpacts imp2 = s2.getImpacts();
        double d2 = imp2.getGrade() / imp2.getIntrinsicRatio();

        // Max dy of closed end(s)
        Point c1 = s1.getInfo().getEnd(rev);
        Point c2 = s2.getInfo().getEnd(rev);
        double closedDy = Math.abs(c1.y - c2.y);

        if (closedDy > params.closedMaxDy) {
            return null;
        }

        double cDy = 1 - (closedDy / params.closedMaxDy);

        // Min dy of open ends
        Point open1 = s1.getInfo().getEnd(!rev);
        Point open2 = s2.getInfo().getEnd(!rev);
        double openDy = Math.abs(open1.y - open2.y);

        if (openDy < params.openMinDyLow) {
            return null;
        }

        double oDy = (openDy - params.openMinDyLow) / (params.openMinDyHigh - params.openMinDyLow);

        // Open ends rather aligned vertically (max bias)
        double invSlope = Math.abs(LineUtil.getInvertedSlope(open1, open2));

        if (invSlope > params.openMaxBias) {
            return null;
        }

        double oBias = 1 - (invSlope / params.openMaxBias);

        return new WedgeInter.Impacts(d1, d2, cDy, oDy, oBias);
    }

    //------------------//
    // createWedgeInter //
    //------------------//
    private WedgeInter createWedgeInter (SegmentInter s1,
                                         SegmentInter s2,
                                         boolean rev,
                                         GradeImpacts impacts)
    {
        Shape shape = rev ? Shape.CRESCENDO : Shape.DIMINUENDO;

        Rectangle box = new Rectangle(s1.getBounds());
        box.add(s2.getBounds());

        // Determine precise closed ends
        Line2D l1 = new Line2D.Double(s1.getInfo().getEnd(true), s1.getInfo().getEnd(false));
        Line2D l2 = new Line2D.Double(s2.getInfo().getEnd(true), s2.getInfo().getEnd(false));

        // Beware s1 and s2 are in no particular order
        final boolean swap;

        if (shape == Shape.CRESCENDO) {
            swap = l2.getY2() < l1.getY2();
        } else {
            swap = l2.getY1() < l1.getY1();
        }

        if (swap) {
            Line2D temp = l1;
            l1 = l2;
            l2 = temp;
        }

        WedgeInter inter = new WedgeInter(l1, l2, box, shape, impacts);

        /* For a wedge, we can restrict the containing systems as just the closest one. */
        Point2D refPoint = (shape == Shape.CRESCENDO) ? l1.getP1() : l1.getP2();
        Staff staff = sheet.getStaffManager().getClosestStaff(refPoint);
        staff.getSystem().getSig().addVertex(inter);

        return inter;
    }

    //---------//
    // getArea //
    //---------//
    private Rectangle getArea (SegmentInfo segment,
                               boolean reverse)
    {
        Point end = segment.getEnd(reverse);
        Rectangle rect = new Rectangle(
                reverse ? end.x : (end.x - params.closedMaxDx + 1),
                end.y,
                params.closedMaxDx,
                0);
        rect.grow(0, (1 + params.closedMaxDy) / 2);
        segment.addAttachment(reverse ? "<" : ">", rect);

        return rect;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Scale.Fraction closedMaxDx = new Scale.Fraction(
                0.2,
                "Maximum abscissa gap between segments ends on closed side");

        private final Scale.Fraction closedMaxDy = new Scale.Fraction(
                0.5,
                "Maximum ordinate gap between segments ends on closed side");

        private final Scale.Fraction openMinDyLow = new Scale.Fraction(
                0.5,
                "Low minimum ordinate gap between segments ends on open side");

        private final Scale.Fraction openMinDyHigh = new Scale.Fraction(
                1.5,
                "High minimum ordinate gap between segments ends on open side");

        private final Constant.Double openMaxBias = new Constant.Double(
                "degrees",
                20,
                "Maximum vertical bias (in degrees) between segments ends on open side");
    }

    //------------//
    // Parameters //
    //------------//
    /**
     * All pre-scaled constants.
     */
    private static class Parameters
    {
        //~ Instance fields ------------------------------------------------------------------------

        final int closedMaxDx;

        final int closedMaxDy;

        final int openMinDyLow;

        final int openMinDyHigh;

        final double openMaxBias;

        //~ Constructors ---------------------------------------------------------------------------
        public Parameters (Scale scale)
        {
            closedMaxDx = scale.toPixels(constants.closedMaxDx);
            closedMaxDy = scale.toPixels(constants.closedMaxDy);
            openMinDyLow = scale.toPixels(constants.openMinDyLow);
            openMinDyHigh = scale.toPixels(constants.openMinDyHigh);
            openMaxBias = Math.tan(Math.toRadians(constants.openMaxBias.getValue()));

            if (logger.isDebugEnabled()) {
                new Dumping().dump(this);
            }
        }
    }

    //-----------//
    // WedgeInfo //
    //-----------//
    private class WedgeInfo
    {
    }
}
