//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     A r c R e t r i e v e r                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.skeleton;

import omr.Main;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.math.BasicLine;
import omr.math.Circle;

import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.Skew;

import omr.skeleton.Arc.ArcShape;
import static omr.skeleton.Skeleton.ARC;
import static omr.skeleton.Skeleton.HIDDEN;
import static omr.skeleton.Skeleton.JUNCTION_PROCESSED;
import static omr.skeleton.Skeleton.PROCESSED;
import static omr.skeleton.Skeleton.allDirs;
import static omr.skeleton.Skeleton.dxs;
import static omr.skeleton.Skeleton.dys;
import static omr.skeleton.Skeleton.getDir;
import static omr.skeleton.Skeleton.isJunction;
import static omr.skeleton.Skeleton.isJunctionProcessed;
import static omr.skeleton.Skeleton.scans;

import omr.util.Navigable;

import ij.process.ByteProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import static java.lang.Math.sin;
import static java.lang.Math.toRadians;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import omr.grid.FilamentLine;
import omr.grid.StaffInfo;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Class {@code ArcRetriever} retrieve all arcs and remember the interesting ones in
 * arcsMap.
 * Each arc has its two ending points flagged with a specific gray value to
 * remember the arc shape.
 * <pre>
 * - scanImage()              // Scan the whole image for arc starts
 *   + scanJunction()         // Scan arcs leaving a junction point
 *   |   + scanArc()
 *   + scanArc()              // Scan one arc
 *       + walkAlong()        // Walk till arc end (forward or backward)
 *       |   + move()         // Move just one pixel
 *       + determineShape()   // Determine the global arc shape
 *       + storeShape()       // Store arc shape in its ending pixels
 * </pre>
 *
 * @author Hervé Bitteur
 */
public class ArcRetriever
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(ArcRetriever.class);

    //~ Enumerations -------------------------------------------------------------------------------
    /**
     * Status for current move along arc.
     */
    private static enum Status
    {
        //~ Enumeration constant initializers ------------------------------------------------------

        /** One more point on arc. */
        CONTINUE,
        /** Arrived at a new junction point. */
        SWITCH,
        /** No more move possible. */
        END;
    }

    //~ Instance fields ----------------------------------------------------------------------------
    /** The related sheet. */
    @Navigable(false)
    private final Sheet sheet;

    /** Global sheet skew. */
    private final Skew skew;

    private final SlursBuilder slursBuilder;

    private final Skeleton skeleton;

    private final ByteProcessor buf;

    /** Current point abscissa. */
    int cx;

    /** Current point ordinate. */
    int cy;

    /** Last direction. */
    int lastDir;

    /** Scale-dependent parameters. */
    private final Parameters params;

    //~ Constructors -------------------------------------------------------------------------------
    public ArcRetriever (Sheet sheet,
                         SlursBuilder slursBuilder,
                         Skeleton skeleton)
    {
        this.sheet = sheet;
        skew = sheet.getSkew();
        this.slursBuilder = slursBuilder;
        this.skeleton = skeleton;
        buf = skeleton.buf;

        params = new Parameters(sheet.getScale());
    }

    //~ Methods ------------------------------------------------------------------------------------
    /**
     * Scan the whole image.
     */
    public void scanImage ()
    {
        for (int x = 1, w = buf.getWidth(); x < w; x++) {
            for (int y = 1, h = buf.getHeight(); y < h; y++) {
                int pix = buf.get(x, y);

                if (pix == ARC) {
                    // Basic arc pixel, not yet processed, scan full arc
                    scanArc(x, y, null, 0);
                } else if (isJunction(pix)) {
                    // Junction pixel, scan arcs linked to this junction point
                    if (!isJunctionProcessed(pix)) {
                        scanJunction(x, y);
                    }
                }
            }
        }

        // Sort arcsEnds by abscissa
        Collections.sort(
                skeleton.arcsEnds,
                new Comparator<Point>()
        {
            @Override
            public int compare (Point p1,
                                Point p2)
            {
                return Integer.compare(p1.x, p2.x);
            }
                });
    }

    /**
     * Record one more point into arc sequence
     *
     * @param reverse scan orientation
     */
    private void addPoint (Arc arc,
                           int cx,
                           int cy,
                           boolean reverse)
    {
        List<Point> points = arc.points;

        if (reverse) {
            points.add(0, new Point(cx, cy));
        } else {
            points.add(new Point(cx, cy));
        }

        buf.set(cx, cy, PROCESSED);
    }

    /**
     * Determine shape for this arc.
     *
     * @param arc arc to evaluate
     * @return the shape classification
     */
    private ArcShape determineShape (Arc arc)
    {
        ///checkBreak(arc);
        List<Point> points = arc.points;

        // Too short?
        if (points.size() < params.arcMinQuorum) {
            ///logger.info("Too short: {}", points.size());
            return ArcShape.SHORT;
        }

        // Check arc is not just a long portion of staff line
        if (isStaffArc(arc)) {
            //logger.info("Staff Line");
            if (arc.getLength() > params.maxStaffArcLength) {
                return ArcShape.IRRELEVANT;
            } else {
                return ArcShape.STAFF_ARC;
            }
        }

        // Straight line?
        // Check mid point for colinearity
        Point p0 = points.get(0);
        Point p1 = points.get(points.size() / 2);
        Point p2 = points.get(points.size() - 1);
        double sinSq = sinSq(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y);

        if (sinSq <= params.maxSinSq) {
            ///logger.info("3 colinear points");

            // This cannot be a slur, but perhaps a straight line.
            // Check mean distance to straight line
            BasicLine line = new BasicLine(points);
            double dist = line.getMeanDistance();

            if (dist <= params.maxLineDistance) {
                // Check this is not a portion of staff line or bar line
                double invSlope = line.getInvertedSlope();

                if (abs(invSlope + skew.getSlope()) <= params.minSlope) {
                    //logger.info("Vertical line");
                    return ArcShape.IRRELEVANT;
                }

                double slope = line.getSlope();

                if (abs(slope - skew.getSlope()) <= params.minSlope) {
                    //logger.info("Horizontal  line");
                }

                ///logger.info("Straight line");
                return ArcShape.LINE;
            }
        }

        // Circle?
        Model fittedModel = slursBuilder.computeModel(points);

        if (fittedModel instanceof CircleModel) {
            arc.model = fittedModel;

            return ArcShape.SLUR;
        }

        // Nothing interesting
        return ArcShape.IRRELEVANT;
    }

    //------------//
    // isStaffArc //
    //------------//
    /**
     * Check whether this arc is simply a part of a staff line.
     *
     * @return true if positive
     */
    private boolean isStaffArc (Arc arc)
    {
        List<Point> points = arc.points;

        if (points.size() < params.minStaffArcLength) {
            return false;
        }

        Point p0 = points.get(0);
        StaffInfo staff = sheet.getStaffManager().getStaffAt(p0);
        FilamentLine line = staff.getClosestLine(p0);
        double maxDist = 0;
        double maxDy = Double.MIN_VALUE;
        double minDy = Double.MAX_VALUE;

        for (int i : new int[]{0, points.size() / 2, points.size() - 1}) {
            Point p = points.get(i);
            double dist = p.y - line.yAt(p.x);
            maxDist = max(maxDist, abs(dist));
            maxDy = max(maxDy, dist);
            minDy = min(minDy, dist);
        }

        return (maxDist < params.minStaffLineDistance)
               && ((maxDy - minDy) < params.minStaffLineDistance);
    }

    /**
     * Update display to show the arc points as "discarded".
     */
    private void hide (Arc arc)
    {
        List<Point> points = arc.points;

        for (int i = 1; i < (points.size() - 1); i++) {
            Point p = points.get(i);

            buf.set(p.x, p.y, HIDDEN);
        }
    }

    /**
     * Try to move to the next point of the arc.
     *
     * @param x       abscissa of current point
     * @param y       ordinate of current point
     * @param reverse current orientation
     * @return code describing the move performed if any.
     *         The new position is stored in (cx, cy).
     */
    private Status move (Arc arc,
                         int x,
                         int y,
                         boolean reverse)
    {
        // First, check for junctions within reach to stop
        for (int dir : scans[lastDir]) {
            cx = x + dxs[dir];
            cy = y + dys[dir];

            int pix = buf.get(cx, cy);

            if (isJunction(pix)) {
                // End of scan for this orientation
                Point junctionPt = new Point(cx, cy);
                arc.setJunction(junctionPt, reverse);
                lastDir = dir;

                return Status.SWITCH;
            }
        }

        // No junction to stop, so move along the arc
        for (int dir : scans[lastDir]) {
            cx = x + dxs[dir];
            cy = y + dys[dir];

            int pix = buf.get(cx, cy);

            if (pix == ARC) {
                lastDir = dir;

                return Status.CONTINUE;
            }
        }

        // The end (dead end or back to start)
        return Status.END;
    }

    /**
     * Scan an arc both ways, starting from a point of the arc,
     * not necessarily an end point.
     *
     * @param x             starting abscissa
     * @param y             starting ordinate
     * @param startJunction start junction point if any
     * @param lastDir       last direction (0 if none)
     * @return the arc fully scanned
     */
    private Arc scanArc (int x,
                         int y,
                         Point startJunction,
                         int lastDir)
    {
        // Remember starting point
        Arc arc = new Arc(startJunction);
        addPoint(arc, x, y, false);

        // Scan arc on normal side -> stopJunction
        walkAlong(arc, x, y, false, lastDir);

        // Scan arc on reverse side -> startJunction if needed
        // If we scanned from a junction, startJunction is already set
        if (startJunction == null) {
            // Set lastDir as the opposite of initial starting dir
            if (arc.points.size() > 1) {
                lastDir = getDir(arc.points.get(1), arc.points.get(0));
            } else if (arc.getJunction(false) != null) {
                lastDir = getDir(arc.getJunction(false), arc.points.get(0));
            }

            walkAlong(arc, x, y, true, lastDir);
        }

        // Check arc shape
        ArcShape shape = determineShape(arc);
        storeShape(arc, shape);

        if (shape.isSlurRelevant()) {
            Point first = arc.points.get(0);
            skeleton.arcsMap.put(first, arc);
            skeleton.arcsEnds.add(first);

            Point last = arc.points.get(arc.points.size() - 1);
            skeleton.arcsMap.put(last, arc);
            skeleton.arcsEnds.add(last);

            return arc;
        } else {
            hide(arc);

            return null;
        }
    }

    /**
     * Scan all arcs connected to this junction point
     *
     * @param x junction point abscissa
     * @param y junction point ordinate
     */
    private void scanJunction (int x,
                               int y)
    {
        Point startJunction = new Point(x, y);
        buf.set(x, y, JUNCTION_PROCESSED);

        // Scan all arcs that depart from this junction point
        for (int dir : allDirs) {
            int nx = x + dxs[dir];
            int ny = y + dys[dir];
            int pix = buf.get(nx, ny);

            if (pix == ARC) {
                scanArc(nx, ny, startJunction, dir);
            } else if (isJunction(pix)) {
                if (!isJunctionProcessed(pix)) {
                    // We have a junction point, touching this one
                    // Use a no-point arg
                    Point stopJunction = new Point(nx, ny);
                    Arc arc = new Arc(startJunction, stopJunction);
                    skeleton.arcsMap.put(startJunction, arc);
                    skeleton.arcsMap.put(stopJunction, arc);
                }
            }
        }
    }

    //-------//
    // sinSq //
    //-------//
    /** Sin**2 of angle between (p0,p1) & (p0,p2). */
    private static double sinSq (int x0,
                                 int y0,
                                 int x1,
                                 int y1,
                                 int x2,
                                 int y2)
    {
        x1 -= x0;
        y1 -= y0;
        x2 -= x0;
        y2 -= y0;

        double vect = (x1 * y2) - (x2 * y1);
        double l1Sq = (x1 * x1) + (y1 * y1);
        double l2Sq = (x2 * x2) + (y2 * y2);

        return (vect * vect) / (l1Sq * l2Sq);
    }

    /**
     * "Store" the arc shape in its ending points, so that scanning
     * from a junction point can immediately know whether the arc
     * is relevant without having to rescan the full arc.
     *
     * @param shape the arc shape
     */
    private void storeShape (Arc arc,
                             ArcShape shape)
    {
        arc.shape = shape;

        List<Point> points = arc.points;

        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        buf.set(first.x, first.y, PROCESSED + shape.ordinal());
        buf.set(last.x, last.y, PROCESSED + shape.ordinal());
    }

    /**
     * Walk along the arc in the desired orientation, starting at
     * (x,y) point, until no more incremental move is possible.
     * Detect the end of a straight line (either horizontal or vertical)
     * and insert an artificial junction point.
     *
     * @param xStart  starting abscissa
     * @param yStart  starting ordinate
     * @param reverse normal (-> stop) or reverse orientation (-> start)
     * @param lastDir arrival at (x,y) if any, 0 if none
     */
    private void walkAlong (Arc arc,
                            int xStart,
                            int yStart,
                            boolean reverse,
                            int lastDir)
    {
        this.lastDir = lastDir;
        cx = xStart;
        cy = yStart;

        Status status;

        while (Status.CONTINUE == (status = move(arc, cx, cy, reverse))) {
            addPoint(arc, cx, cy, reverse);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        final Constant.Double maxAlpha = new Constant.Double(
                "degree",
                2.5,
                "Maximum angle (in degrees) for 3 points colinearity");

        final Scale.Fraction arcMinQuorum = new Scale.Fraction(
                1.5,
                "Minimum arc length for quorum");

        final Scale.Fraction maxLineDistance = new Scale.Fraction(
                0.1,
                "Maximum distance from straight line");

        final Scale.Fraction minStaffArcLength = new Scale.Fraction(
                0.5,
                "Minimum length for a staff arc");

        final Scale.Fraction maxStaffArcLength = new Scale.Fraction(
                5.0,
                "Maximum length for a staff arc");

        final Scale.Fraction minStaffLineDistance = new Scale.Fraction(
                0.15,
                "Minimum distance from staff line");

        final Constant.Double minSlope = new Constant.Double(
                "(co)tangent",
                0.03,
                "Minimum (inverted) slope, to detect vertical and horizontal lines");
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

        final int arcMinQuorum;

        final int minStaffArcLength;

        final int maxStaffArcLength;

        final double minStaffLineDistance;

        final double maxSinSq;

        final double maxLineDistance;

        final double minSlope;

        //~ Constructors ---------------------------------------------------------------------------
        /**
         * Creates a new Parameters object.
         *
         * @param scale the scaling factor
         */
        public Parameters (Scale scale)
        {
            double maxSin = sin(toRadians(constants.maxAlpha.getValue()));

            maxSinSq = maxSin * maxSin;
            arcMinQuorum = scale.toPixels(constants.arcMinQuorum);
            maxLineDistance = scale.toPixelsDouble(constants.maxLineDistance);
            minSlope = constants.minSlope.getValue();
            minStaffArcLength = scale.toPixels(constants.minStaffArcLength);
            maxStaffArcLength = scale.toPixels(constants.maxStaffArcLength);
            minStaffLineDistance = scale.toPixelsDouble(constants.minStaffLineDistance);

            if (logger.isDebugEnabled()) {
                Main.dumping.dump(this);
            }
        }
    }
}
